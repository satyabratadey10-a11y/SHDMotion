#pragma once

#include <cstdint>
#include <vector>

namespace motionengine {

struct Point {
    float x;
    float y;
};

struct BoundingBox {
    float x;
    float y;
    float width;
    float height;
};

struct StabilizationData {
    float deltaX;
    float deltaY;
    float rotation;
};

enum class PixelFormat : int {
    Rgba = 0,
    Gray = 1,
    Yuv420 = 2
};

std::vector<Point> initializeTrackingPoints(
    const uint8_t* frame, int width, int height, int stride,
    PixelFormat format, int columns, int rows);
std::vector<Point> trackPoints(
    const uint8_t* previous, const uint8_t* current, int width, int height,
    int stride, PixelFormat format, const std::vector<Point>& points,
    int windowRadius);
BoundingBox trackBoundingBox(
    const uint8_t* previous, const uint8_t* current, int width, int height,
    int stride, PixelFormat format, BoundingBox box, int searchRadius,
    int templateRadius);
StabilizationData estimateStabilization(
    const uint8_t* previous, const uint8_t* current, int width, int height,
    int stride, PixelFormat format, int maxPoints);
std::vector<Point> warpMaskPolygon(
    const uint8_t* previous, const uint8_t* current, int width, int height,
    int stride, PixelFormat format, const std::vector<Point>& polygon,
    int windowRadius);

}
