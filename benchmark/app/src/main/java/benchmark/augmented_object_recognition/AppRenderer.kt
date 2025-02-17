/*
 * Copyright 2021, Brown University, Providence, RI.
 * Rahul Shahi, Sherief Reda, Seif Abdelaziz
 *
 *                        All Rights Reserved
 *
 * Permission to use, copy, modify, and distribute this software and
 * its documentation for any purpose other than its incorporation into a
 * commercial product or service is hereby granted without fee, provided
 * that the above copyright notice appear in all copies and that both
 * that copyright notice and this permission notice appear in supporting
 * documentation, and that the name of Brown University not be used in
 * advertising or publicity pertaining to distribution of the software
 * without specific, written prior permission.
 *
 * BROWN UNIVERSITY DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE,
 * INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR ANY
 * PARTICULAR PURPOSE.  IN NO EVENT SHALL BROWN UNIVERSITY BE LIABLE FOR
 * ANY SPECIAL, INDIRECT OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 */

/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package benchmark.augmented_object_recognition;

import android.app.Activity
import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import benchmark.augmented_image.AugmentedImageActivity
import benchmark.augmented_object_generation.AugmentedObjectGenerationActivity
import benchmark.augmented_object_recognition.classification.DetectedObjectResult
import benchmark.augmented_object_recognition.classification.MLKitObjectDetector
import benchmark.augmented_object_recognition.classification.ObjectDetector
import benchmark.augmented_object_recognition.render.LabelRender
import benchmark.augmented_object_recognition.render.PointCloudRender
import benchmark.common.helpers.DisplayRotationHelper
import benchmark.common.helpers.TrackingStateHelper
import benchmark.common.samplerender.SampleRender
import benchmark.common.samplerender.arcore.BackgroundRenderer
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.IntBuffer
import java.util.*


/**
 * Renders the HelloAR application into using our example Renderer.
 */
class AppRenderer(val recognitionActivity: AugmentedObjectRecognitionActivity) : DefaultLifecycleObserver, SampleRender.Renderer, CoroutineScope by MainScope() {
  companion object {
    val TAG = "HelloArRenderer"
  }

  lateinit var viewRecognition: AugmentedObjectRecognitionActivityView

  private lateinit var trackingStateHelper: TrackingStateHelper

  val displayRotationHelper = DisplayRotationHelper(recognitionActivity)
  lateinit var backgroundRenderer: BackgroundRenderer
  val pointCloudRender = PointCloudRender()
  val labelRenderer = LabelRender()

  private val PHASE_TRACK_ID = UUID.fromString("53069eb5-21ef-4946-b71c-6ac4979216a7")

  val viewMatrix = FloatArray(16)
  val projectionMatrix = FloatArray(16)
  val viewProjectionMatrix = FloatArray(16)

  val arLabeledAnchors = Collections.synchronizedList(mutableListOf<ARLabeledAnchor>())
  var scanButtonWasPressed = false

  val mlKitAnalyzer = MLKitObjectDetector(recognitionActivity)

  var currentAnalyzer: ObjectDetector = mlKitAnalyzer
  var currentPhase = 1

  private var hasTimerExtension = false
  private val TIME_ELAPSED_EXT = 0x88BF
  private val NUM_QUERIES = 10
  private var timeQueries: IntArray = IntArray(NUM_QUERIES)
  private var queryBuffer: IntArray = IntArray(1)
  private var queryIndex = 0

  private fun cleanupCollectionResources() {
    try {
      viewRecognition.fpsLog?.flush()
      viewRecognition.fpsLog?.close()
      for (i in 0 until NUM_QUERIES) {
        if (timeQueries[i] >= 0) {
          GLES30.glDeleteQueries(1, timeQueries, i)
        }
      }
    } catch (e: IOException) {
      Log.e(TAG, "Exception closing frame log: ", e)
    }
  }

  private fun saveLastFrame(width: Int, height: Int) {
    val size = width * height
    val imageArray = IntArray(size)
    val intBuffer = IntBuffer.allocate(size)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, intBuffer)
    val imageArray2 = intBuffer.array()
    for (i in 0 until height) {
      for (j in 0 until width) {
        imageArray[(height - i - 1) * width + j] = imageArray2[i * width + j]
      }
    }
    bitmap.copyPixelsFromBuffer(IntBuffer.wrap(imageArray))
    val imageFile: File =
      File(recognitionActivity.getExternalFilesDir(null).toString() + "/" + recognitionActivity.fileName?.replace(".mp4", ".jpg"))
    try {
      imageFile.delete()
      val fileOutputStream = FileOutputStream(imageFile)
      bitmap.compress(Bitmap.CompressFormat.JPEG, 50, fileOutputStream)
      fileOutputStream.close()
    } catch (e: IOException) {
      Log.e(recognitionActivity.TAG, "Failed to save preview image: ", e)
    }
  }

  override fun onResume(owner: LifecycleOwner) {
    displayRotationHelper.onResume()
  }

  override fun onPause(owner: LifecycleOwner) {
    displayRotationHelper.onPause()
  }

  fun bindView(viewRecognition: AugmentedObjectRecognitionActivityView) {
    this.viewRecognition = viewRecognition

    trackingStateHelper = TrackingStateHelper(viewRecognition.recognitionActivity)
  }

  override fun onSurfaceCreated(render: SampleRender) {
    backgroundRenderer = BackgroundRenderer(render).apply {
      setUseDepthVisualization(render, false)
    }
    pointCloudRender.onSurfaceCreated(render)
    labelRenderer.onSurfaceCreated(render)

    queryBuffer[0] = 0
    queryIndex = 0
    for (i in 0 until NUM_QUERIES) {
      timeQueries[i] = -1
    }
    val extensions = GLES30.glGetString(GLES30.GL_EXTENSIONS)
    hasTimerExtension = extensions.contains(" GL_EXT_disjoint_timer_query ")
  }

  override fun onSurfaceChanged(render: SampleRender?, width: Int, height: Int) {
    displayRotationHelper.onSurfaceChanged(width, height)
  }

  var objectResults: List<DetectedObjectResult>? = null

  override fun onDrawFrame(render: SampleRender) {
    val frameTime = System.currentTimeMillis()

    var session = recognitionActivity.arCoreSessionHelper.sessionCache ?: return
    if (session.playbackStatus == PlaybackStatus.FINISHED) {
      recognitionActivity.arCoreSessionHelper.onDestroy(recognitionActivity) // close session
      saveLastFrame(viewRecognition.render!!.viewportWidth, viewRecognition.render!!.viewportHeight)
      try {
        if (viewRecognition.fpsLog != null) {
          viewRecognition.fpsLog?.flush()
          viewRecognition.fpsLog?.close()
          viewRecognition.fpsLog = null
        }
      } catch (e: IOException) {
      }
      recognitionActivity.setResult(Activity.RESULT_OK)
      recognitionActivity.finish()
      return
    }
    render?.clear(null, 0f, 0f, 0f, 1f)
    session.setCameraTextureNames(intArrayOf(backgroundRenderer.cameraColorTexture.textureId))

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session)

    var processTime = System.currentTimeMillis()
    val frame = try {
      session.update()
    } catch (e: CameraNotAvailableException) {
      Log.e(TAG, "Camera not available during onDrawFrame", e)
      showSnackbar("Camera not available. Try restarting the app.")
      return
    }

    // Get camera and projection matrices.
    val camera = frame.camera
    camera.getViewMatrix(viewMatrix, 0)
    camera.getProjectionMatrix(projectionMatrix, 0, 0.01f, 100.0f)
    Matrix.multiplyMM(viewProjectionMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

    processTime = System.currentTimeMillis() - processTime

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.trackingState)

    if (session.playbackStatus == PlaybackStatus.FINISHED) {
      recognitionActivity.setResult(Activity.RESULT_OK)
      cleanupCollectionResources()
      recognitionActivity.finish()
      return
    }

    // Read recording phase data.
    if (session.playbackStatus == PlaybackStatus.OK) {
      for (trackData in frame.getUpdatedTrackData(PHASE_TRACK_ID)) {
        val payload = trackData.data
        val intBuffer: IntBuffer = payload.asIntBuffer()
        val phase = IntArray(1)
        intBuffer.get(phase)
        currentPhase = phase[0]
        break
      }
    }

    // Read recording scan button input data.
    if (session.playbackStatus == PlaybackStatus.OK) {
      if (!frame.getUpdatedTrackData(viewRecognition.SCAN_TRACK_ID).isEmpty()) {
        scanButtonWasPressed = true
      }
    }

    backgroundRenderer.updateDisplayGeometry(frame)
    backgroundRenderer.drawBackground(render)

    var handleInputTime = System.currentTimeMillis()
    if (scanButtonWasPressed) {
      scanButtonWasPressed = false
      val cameraImage = frame.tryAcquireCameraImage()
      if (cameraImage != null) {
        // Call our ML model on an IO thread.
        launch(Dispatchers.IO) {
          val cameraId = session.cameraConfig.cameraId
          val imageRotation = displayRotationHelper.getCameraSensorToDisplayRotation(cameraId)
          objectResults = currentAnalyzer.analyze(cameraImage, imageRotation)
          cameraImage.close()
        }
      }
    }

    /** If results were completed this frame, create [Anchor]s from model results. */
    val objects = objectResults
    if (objects != null) {
      objectResults = null
      Log.i(TAG, "$currentAnalyzer got objects: $objects")
      val anchors = objects.mapNotNull { obj ->
        val (atX, atY) = obj.centerCoordinate
        val anchor = createAnchor(atX.toFloat(), atY.toFloat(), frame) ?: return@mapNotNull null
        Log.i(TAG, "Created anchor ${anchor.pose} from hit test")
        ARLabeledAnchor(anchor, obj.label)
      }
      arLabeledAnchors.addAll(anchors)
    }

    handleInputTime = System.currentTimeMillis() - handleInputTime

    // Handle tracking failures.
    if (camera.trackingState != TrackingState.TRACKING) {
      return
    }

    // Draw point cloud.
    frame.acquirePointCloud().use { pointCloud ->
      pointCloudRender.drawPointCloud(render, pointCloud, viewProjectionMatrix)
    }

    if (!hasTimerExtension) {
      showSnackbar("OpenGL extension EXT_disjoint_timer_query is unavailable on this device")
      return
    }
    if (timeQueries[queryIndex] < 0) {
      GLES30.glGenQueries(1, timeQueries, queryIndex)
    }
    if (timeQueries[(queryIndex + 1) % NUM_QUERIES] >= 0) {
      val queryResult = IntBuffer.allocate(1)
      GLES30.glGetQueryObjectuiv(
        timeQueries[(queryIndex + 1) % NUM_QUERIES],
        GLES30.GL_QUERY_RESULT_AVAILABLE,
        queryResult
      )
      if (queryResult.get() == GLES30.GL_TRUE) {
        GLES30.glGetQueryObjectuiv(
          timeQueries[(queryIndex + 1) % NUM_QUERIES],
          GLES30.GL_QUERY_RESULT,
          queryBuffer,
          0
        )
      }
    }
    GLES30.glBeginQuery(TIME_ELAPSED_EXT, timeQueries[queryIndex])

    // Draw labels at their anchor position.
    for (arDetectedObject in arLabeledAnchors) {
      val anchor = arDetectedObject.anchor
      if (anchor.trackingState != TrackingState.TRACKING) continue
      labelRenderer.draw(
        render,
        viewProjectionMatrix,
        anchor.pose,
        camera.pose,
        arDetectedObject.label
      )
    }

    GLES30.glEndQuery(TIME_ELAPSED_EXT)
    queryIndex = (queryIndex + 1) % NUM_QUERIES
    if (viewRecognition.fpsLog != null) {
      val data =
        currentPhase.toString() + "," + frameTime + "," + processTime + "," + handleInputTime + "," + queryBuffer[0] + "," + (System.currentTimeMillis() - frameTime) + "\n";
      viewRecognition.fpsLog!!.write(data)
    }
  }

  /**
   * Utility method for [Frame.acquireCameraImage] that maps [NotYetAvailableException] to `null`.
   */
  fun Frame.tryAcquireCameraImage() = try {
    acquireCameraImage()
  } catch (e: NotYetAvailableException) {
    null
  } catch (e: Throwable) {
    throw e
  }

  fun showSnackbar(message: String): Unit =
    recognitionActivity.viewRecognition.snackbarHelper.showError(recognitionActivity, message)

  private fun hideSnackbar() =
    recognitionActivity.viewRecognition.snackbarHelper.hide(recognitionActivity)

  /**
   * Temporary arrays to prevent allocations in [createAnchor].
   */
  private val convertFloats = FloatArray(4)
  private val convertFloatsOut = FloatArray(4)

  /** Create an anchor using (x, y) coordinates in the [Coordinates2d.IMAGE_PIXELS] coordinate space. */
  fun createAnchor(xImage: Float, yImage: Float, frame: Frame): Anchor? {
    // IMAGE_PIXELS -> VIEW
    convertFloats[0] = xImage
    convertFloats[1] = yImage
    frame.transformCoordinates2d(
      Coordinates2d.IMAGE_PIXELS,
      convertFloats,
      Coordinates2d.VIEW,
      convertFloatsOut
    )

    // Conduct a hit test using the VIEW coordinates
    val hits = frame.hitTest(convertFloatsOut[0], convertFloatsOut[1])
    val result = hits.getOrNull(0) ?: return null
    return result.trackable.createAnchor(result.hitPose)
  }
}

data class ARLabeledAnchor(val anchor: Anchor, val label: String)