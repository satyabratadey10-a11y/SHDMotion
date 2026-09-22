package com.tracker.motionengine

import java.nio.ByteBuffer

class MotionEngine {
    enum class PixelFormat(val nativeValue: Int) { RGBA(0), GRAY(1), YUV420(2) }
    data class Point(val x: Float, val y: Float)
    data class BoundingBox(val x: Float, val y: Float, val width: Float, val height: Float)
    data class Stabilization(val deltaX: Float, val deltaY: Float, val rotationRadians: Float)

    companion object {
        init {
            System.loadLibrary("motionengine")
        }

        @JvmStatic
        fun initializeTrackingPoints(frame: ByteBuffer, width: Int, height: Int, stride: Int,
                                     columns: Int = 8, rows: Int = 6,
                                     format: PixelFormat = PixelFormat.RGBA): List<Point> =
            MotionEngine().nativeInitializeTrackingPoints(frame, width, height, stride,
                format.nativeValue, columns, rows).toPoints()

        @JvmStatic
        fun trackPoints(previous: ByteBuffer, current: ByteBuffer, width: Int, height: Int, stride: Int,
                        points: List<Point>, windowRadius: Int = 3,
                        format: PixelFormat = PixelFormat.RGBA): List<Point> {
            val values = points.flatMap { listOf(it.x, it.y) }.toFloatArray()
            return MotionEngine().nativeTrackPoints(previous, current, width, height, stride,
                format.nativeValue, values, windowRadius).toPoints()
        }

        @JvmStatic
        fun trackBoundingBox(previous: ByteBuffer, current: ByteBuffer, width: Int, height: Int,
                             stride: Int, box: BoundingBox, searchRadius: Int = 24,
                             templateRadius: Int = 16,
                             format: PixelFormat = PixelFormat.RGBA): BoundingBox {
            val result = MotionEngine().nativeTrackBoundingBox(previous, current, width, height, stride,
                format.nativeValue, box.x, box.y, box.width, box.height, searchRadius, templateRadius)
            return BoundingBox(result[0], result[1], result[2], result[3])
        }

        @JvmStatic
        fun estimateStabilization(previous: ByteBuffer, current: ByteBuffer, width: Int, height: Int,
                                  stride: Int, maxPoints: Int = 64,
                                  format: PixelFormat = PixelFormat.RGBA): Stabilization {
            val result = MotionEngine().nativeEstimateStabilization(previous, current, width, height,
                stride, format.nativeValue, maxPoints)
            return Stabilization(result[0], result[1], result[2])
        }

        @JvmStatic
        fun warpMaskPolygon(previous: ByteBuffer, current: ByteBuffer, width: Int, height: Int,
                            stride: Int, polygon: List<Point>, windowRadius: Int = 3,
                            format: PixelFormat = PixelFormat.RGBA): List<Point> {
            val values = polygon.flatMap { listOf(it.x, it.y) }.toFloatArray()
            return MotionEngine().nativeWarpMaskPolygon(previous, current, width, height, stride,
                format.nativeValue, values, windowRadius).toPoints()
        }
    }

    private fun FloatArray.toPoints(): List<Point> =
        if (isEmpty()) emptyList() else asList().chunked(2).map { Point(it[0], it[1]) }

    private external fun nativeInitializeTrackingPoints(frame: ByteBuffer, width: Int, height: Int,
                                                       stride: Int, format: Int, columns: Int, rows: Int): FloatArray
    private external fun nativeTrackPoints(previous: ByteBuffer, current: ByteBuffer, width: Int,
                                          height: Int, stride: Int, format: Int, points: FloatArray, radius: Int): FloatArray
    private external fun nativeTrackBoundingBox(previous: ByteBuffer, current: ByteBuffer, width: Int,
                                              height: Int, stride: Int, format: Int, x: Float, y: Float,
                                              widthBox: Float, heightBox: Float, searchRadius: Int,
                                              templateRadius: Int): FloatArray
    private external fun nativeEstimateStabilization(previous: ByteBuffer, current: ByteBuffer, width: Int,
                                                    height: Int, stride: Int, format: Int,
                                                    maxPoints: Int): FloatArray
    private external fun nativeWarpMaskPolygon(previous: ByteBuffer, current: ByteBuffer, width: Int,
                                              height: Int, stride: Int, format: Int,
                                              polygon: FloatArray, radius: Int): FloatArray
}
