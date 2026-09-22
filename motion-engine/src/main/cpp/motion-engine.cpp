#include "motion-engine.h"

#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <deque>
#include <limits>
#include <mutex>

namespace {
using motionengine::BoundingBox;
using motionengine::PixelFormat;
using motionengine::Point;
using motionengine::StabilizationData;
constexpr float kInvalid = std::numeric_limits<float>::quiet_NaN();

inline bool inside(float x, float y, int width, int height, int margin = 1) {
    return x >= margin && y >= margin && x < width - margin - 1 && y < height - margin - 1;
}

inline float sample(const uint8_t* image, float x, float y, int width, int height,
                    int stride, PixelFormat format) {
    if (!inside(x, y, width, height, 0)) return 0.0f;
    const int x0 = static_cast<int>(x), y0 = static_cast<int>(y);
    const float fx = x - x0, fy = y - y0;
    auto at = [&](int px, int py) {
        const uint8_t* row = image + static_cast<size_t>(py) * stride;
        if (format == PixelFormat::Rgba) {
            const uint8_t* p = row + px * 4;
            return 0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2];
        }
        return static_cast<float>(row[px]);
    };
    const int x1 = std::min(x0 + 1, width - 1), y1 = std::min(y0 + 1, height - 1);
    return (1.0f - fy) * ((1.0f - fx) * at(x0, y0) + fx * at(x1, y0)) +
           fy * ((1.0f - fx) * at(x0, y1) + fx * at(x1, y1));
}

Point lk(const uint8_t* previous, const uint8_t* current, int width, int height,
         int stride, PixelFormat format, Point start, int radius) {
    if (!inside(start.x, start.y, width, height, radius + 2)) return {kInvalid, kInvalid};
    Point result = start;
    for (int iteration = 0; iteration < 4; ++iteration) {
        double gxx = 0.0, gxy = 0.0, gyy = 0.0, gxt = 0.0, gyt = 0.0;
        for (int dy = -radius; dy <= radius; ++dy) {
            for (int dx = -radius; dx <= radius; ++dx) {
                const float x = start.x + dx, y = start.y + dy;
                const float ix = (sample(previous, x + 1, y, width, height, stride, format) -
                                  sample(previous, x - 1, y, width, height, stride, format)) * 0.5f;
                const float iy = (sample(previous, x, y + 1, width, height, stride, format) -
                                  sample(previous, x, y - 1, width, height, stride, format)) * 0.5f;
                const float temporal = sample(current, result.x + dx, result.y + dy, width, height, stride, format) -
                                       sample(previous, x, y, width, height, stride, format);
                gxx += ix * ix; gxy += ix * iy; gyy += iy * iy;
                gxt += ix * temporal; gyt += iy * temporal;
            }
        }
        const double determinant = gxx * gyy - gxy * gxy;
        if (determinant < 1e-3) return {kInvalid, kInvalid};
        const float ux = static_cast<float>((gyy * gxt - gxy * gyt) / determinant);
        const float uy = static_cast<float>((gxx * gyt - gxy * gxt) / determinant);
        result.x -= ux;
        result.y -= uy;
        if (ux * ux + uy * uy < 0.01f) break;
        if (!inside(result.x, result.y, width, height, radius + 2)) return {kInvalid, kInvalid};
    }
    return result;
}

std::mutex stabilizationMutex;
std::deque<StabilizationData> stabilizationHistory;

}  // namespace

namespace motionengine {

std::vector<Point> initializeTrackingPoints(const uint8_t* frame, int width, int height,
                                            int stride, PixelFormat format, int columns, int rows) {
    std::vector<Point> result;
    if (!frame || width < 16 || height < 16 || columns < 1 || rows < 1) return result;
    columns = std::min(columns, width / 8);
    rows = std::min(rows, height / 8);
    for (int row = 1; row <= rows; ++row) {
        const float y = row * static_cast<float>(height) / (rows + 1);
        for (int column = 1; column <= columns; ++column) {
            const float x = column * static_cast<float>(width) / (columns + 1);
            float best = -1.0f; Point bestPoint{x, y};
            for (int oy = -3; oy <= 3; ++oy) for (int ox = -3; ox <= 3; ++ox) {
                const float gx = sample(frame, x + ox + 1, y + oy, width, height, stride, format) -
                                 sample(frame, x + ox - 1, y + oy, width, height, stride, format);
                const float gy = sample(frame, x + ox, y + oy + 1, width, height, stride, format) -
                                 sample(frame, x + ox, y + oy - 1, width, height, stride, format);
                if (gx * gx + gy * gy > best) { best = gx * gx + gy * gy; bestPoint = {x + ox, y + oy}; }
            }
            result.push_back(bestPoint);
        }
    }
    return result;
}

std::vector<Point> trackPoints(const uint8_t* previous, const uint8_t* current, int width, int height,
                               int stride, PixelFormat format, const std::vector<Point>& points,
                               int windowRadius) {
    std::vector<Point> result;
    result.reserve(points.size());
    for (const Point& point : points)
        result.push_back(lk(previous, current, width, height, stride, format, point,
                            std::max(1, std::min(windowRadius, 12))));
    return result;
}

BoundingBox trackBoundingBox(const uint8_t* previous, const uint8_t* current, int width, int height,
                             int stride, PixelFormat format, BoundingBox box, int searchRadius,
                             int templateRadius) {
    const int cx = static_cast<int>(box.x + box.width * 0.5f);
    const int cy = static_cast<int>(box.y + box.height * 0.5f);
    const int half = std::max(1, std::min(templateRadius, 32));
    float bestError = std::numeric_limits<float>::max(); int bestX = cx, bestY = cy;
    for (int y = std::max(half, cy - searchRadius); y <= std::min(height - half - 1, cy + searchRadius); ++y) {
        for (int x = std::max(half, cx - searchRadius); x <= std::min(width - half - 1, cx + searchRadius); ++x) {
            double error = 0.0;
            for (int dy = -half; dy <= half; ++dy) for (int dx = -half; dx <= half; ++dx) {
                const float delta = sample(previous, cx + dx, cy + dy, width, height, stride, format) -
                                    sample(current, x + dx, y + dy, width, height, stride, format);
                error += delta * delta;
            }
            if (error < bestError) { bestError = static_cast<float>(error); bestX = x; bestY = y; }
        }
    }
    return {box.x + bestX - cx, box.y + bestY - cy, box.width, box.height};
}

StabilizationData estimateStabilization(const uint8_t* previous, const uint8_t* current, int width,
                                        int height, int stride, PixelFormat format, int maxPoints) {
    const auto seeds = initializeTrackingPoints(previous, width, height, stride, format,
                                                std::max(2, static_cast<int>(std::sqrt(maxPoints))),
                                                std::max(2, static_cast<int>(std::sqrt(maxPoints))));
    const auto tracked = trackPoints(previous, current, width, height, stride, format, seeds, 3);
    double oldX = 0, oldY = 0, newX = 0, newY = 0; int count = 0;
    for (size_t i = 0; i < seeds.size(); ++i) if (std::isfinite(tracked[i].x)) {
        oldX += seeds[i].x; oldY += seeds[i].y; newX += tracked[i].x; newY += tracked[i].y; ++count;
    }
    StabilizationData value{0, 0, 0};
    if (count >= 2) {
        oldX /= count; oldY /= count; newX /= count; newY /= count;
        double cross = 0, dot = 0;
        for (size_t i = 0; i < seeds.size(); ++i) if (std::isfinite(tracked[i].x)) {
            const double ax = seeds[i].x - oldX, ay = seeds[i].y - oldY;
            const double bx = tracked[i].x - newX, by = tracked[i].y - newY;
            cross += ax * by - ay * bx; dot += ax * bx + ay * by;
        }
        value = {static_cast<float>(newX - oldX), static_cast<float>(newY - oldY),
                 static_cast<float>(std::atan2(cross, dot))};
    }
    std::lock_guard<std::mutex> lock(stabilizationMutex);
    stabilizationHistory.push_back(value);
    if (stabilizationHistory.size() > 8) stabilizationHistory.pop_front();
    StabilizationData average{0, 0, 0};
    for (const auto& item : stabilizationHistory) {
        average.deltaX += item.deltaX; average.deltaY += item.deltaY; average.rotation += item.rotation;
    }
    const float divisor = static_cast<float>(stabilizationHistory.size());
    return {average.deltaX / divisor, average.deltaY / divisor, average.rotation / divisor};
}

std::vector<Point> warpMaskPolygon(const uint8_t* previous, const uint8_t* current, int width,
                                   int height, int stride, PixelFormat format,
                                   const std::vector<Point>& polygon, int windowRadius) {
    return trackPoints(previous, current, width, height, stride, format, polygon, windowRadius);
}
}  // namespace motionengine

static uint8_t* direct(JNIEnv* env, jobject buffer) {
    return buffer ? static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer)) : nullptr;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tracker_motionengine_MotionEngine_nativeInitializeTrackingPoints(JNIEnv* env, jobject thiz,
    jobject frame, jint width, jint height, jint stride, jint format, jint columns, jint rows) {
    (void)thiz;
    const auto points = motionengine::initializeTrackingPoints(direct(env, frame), width, height, stride,
        static_cast<PixelFormat>(format), columns, rows);
    jfloatArray output = env->NewFloatArray(static_cast<jsize>(points.size() * 2));
    if (!output) return nullptr;
    std::vector<float> values; values.reserve(points.size() * 2);
    for (const auto& p : points) { values.push_back(p.x); values.push_back(p.y); }
    if (!values.empty()) {
        env->SetFloatArrayRegion(output, 0, static_cast<jsize>(values.size()), values.data());
    }
    return output;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tracker_motionengine_MotionEngine_nativeTrackPoints(JNIEnv* env, jobject thiz, jobject previous,
    jobject current, jint width, jint height, jint stride, jint format, jfloatArray input, jint radius) {
    (void)thiz;
    if (!direct(env, previous) || !direct(env, current) || !input) return nullptr;
    const jsize length = env->GetArrayLength(input); std::vector<float> values(length);
    env->GetFloatArrayRegion(input, 0, length, values.data()); std::vector<Point> points;
    for (jsize i = 0; i + 1 < length; i += 2) points.push_back({values[i], values[i + 1]});
    const auto tracked = motionengine::trackPoints(direct(env, previous), direct(env, current), width, height,
        stride, static_cast<PixelFormat>(format), points, radius);
    for (size_t i = 0; i < tracked.size(); ++i) { values[i * 2] = tracked[i].x; values[i * 2 + 1] = tracked[i].y; }
    env->SetFloatArrayRegion(input, 0, length, values.data());
    return input;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tracker_motionengine_MotionEngine_nativeTrackBoundingBox(JNIEnv* env, jobject thiz, jobject previous,
    jobject current, jint width, jint height, jint stride, jint format, jfloat x, jfloat y, jfloat w, jfloat h,
    jint searchRadius, jint templateRadius) {
    (void)thiz;
    const auto box = motionengine::trackBoundingBox(direct(env, previous), direct(env, current), width, height,
        stride, static_cast<PixelFormat>(format), {x, y, w, h}, searchRadius, templateRadius);
    jfloatArray output = env->NewFloatArray(4); const float values[] = {box.x, box.y, box.width, box.height};
    if (output) env->SetFloatArrayRegion(output, 0, 4, values); return output;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tracker_motionengine_MotionEngine_nativeEstimateStabilization(JNIEnv* env, jobject thiz, jobject previous,
    jobject current, jint width, jint height, jint stride, jint format, jint maxPoints) {
    (void)thiz;
    const auto value = motionengine::estimateStabilization(direct(env, previous), direct(env, current), width,
        height, stride, static_cast<PixelFormat>(format), maxPoints);
    jfloatArray output = env->NewFloatArray(3); const float values[] = {value.deltaX, value.deltaY, value.rotation};
    if (output) env->SetFloatArrayRegion(output, 0, 3, values); return output;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_tracker_motionengine_MotionEngine_nativeWarpMaskPolygon(JNIEnv* env, jobject thiz, jobject previous,
    jobject current, jint width, jint height, jint stride, jint format, jfloatArray input, jint radius) {
    (void)thiz;
    if (!direct(env, previous) || !direct(env, current) || !input) return nullptr;
    const jsize length = env->GetArrayLength(input); std::vector<float> values(length);
    env->GetFloatArrayRegion(input, 0, length, values.data()); std::vector<Point> polygon;
    for (jsize i = 0; i + 1 < length; i += 2) polygon.push_back({values[i], values[i + 1]});
    const auto warped = motionengine::warpMaskPolygon(direct(env, previous), direct(env, current), width, height,
        stride, static_cast<PixelFormat>(format), polygon, radius);
    for (size_t i = 0; i < warped.size(); ++i) { values[i * 2] = warped[i].x; values[i * 2 + 1] = warped[i].y; }
    env->SetFloatArrayRegion(input, 0, length, values.data()); return input;
}
