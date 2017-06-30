package com.miracle.view.imageeditor.view

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.support.v4.util.ArrayMap
import com.miracle.view.imageeditor.LayerViewProvider
import com.miracle.view.imageeditor.Utils
import com.miracle.view.imageeditor.bean.CropSaveState
import com.miracle.view.imageeditor.bean.EditorCacheData
import com.miracle.view.imageeditor.layer.CropView
import com.miracle.view.imageeditor.layer.LayerCacheNode
import com.miracle.view.imageeditor.logD1
import com.miracle.view.imageeditor.recycleBitmap

/**
 * Created by lxw
 */
class CropHelper(private val mCropView: CropView, private val mCropDetailsView: CropDetailsView,
                 private val mProvider: LayerViewProvider) : CropDetailsView.OnCropOperationListener
        , LayerCacheNode {
    private var mCropSaveState: CropSaveState? = null
    private val mCropScaleRatio = 0.95f
    private val mRootEditorDelegate = mProvider.getRootEditorDelegate()
    private val mFuncAndActionBarAnimHelper = mProvider.getFuncAndActionBarAnimHelper()
    private val mLayerComposite = mProvider.getLayerCompositeView()
    private val mSavedStateMap = ArrayMap<String, CropSaveState>()

    init {
        mCropView.onCropViewUpdatedListener = object : CropView.OnCropViewUpdatedListener {
            override fun onCropViewUpdated() {
                mCropDetailsView.setRestoreTextStatus(true)
            }
        }
        mCropDetailsView.cropListener = this
    }

    override fun onCropRotation(degree: Float) {
        mRootEditorDelegate.setRotationBy(degree)
    }

    override fun onCropCancel() {
        closeCropDetails()
    }

    override fun onCropConfirm() {
        if (mCropView.isCropWindowEdit() || mCropSaveState != null) {
            val lastMatrix = mRootEditorDelegate.getSupportMatrix()
            val lastBitmap = mRootEditorDelegate.getDisplayBitmap()
            val lastDisplayRect = mRootEditorDelegate.getDisplayingRect()
            val lastBaseMatrix = mRootEditorDelegate.getBaseLayoutMatrix()
            val lastCropRect = mCropView.getCropRect()
            mCropSaveState?.supportMatrix = lastMatrix
            mCropSaveState?.cropRect = lastCropRect
            if (mCropSaveState == null && lastBitmap != null && lastDisplayRect != null) {
                mCropSaveState = CropSaveState(lastBitmap, lastDisplayRect, lastBaseMatrix, lastMatrix, lastCropRect)
            }
            mCropSaveState?.let {
                val cropBitmap = getCropBitmap(lastCropRect, it.originalBitmap, it.supportMatrix, it.originalDisplayRectF)
                if (it.cropBitmap != cropBitmap) {
                    recycleBitmap(it.cropBitmap)
                    it.cropBitmap = cropBitmap
                    logD1("onCropConfirm,crop==lastCrop")
                }
                if (cropBitmap == it.originalBitmap) {
                    it.cropBitmap = null
                    mCropSaveState = null
                    logD1("onCropConfirm,crop==original,set cropBitmap,mCropSaveState=null")
                }
            }
        }
        closeCropDetails()

    }

    override fun onCropRestore() {
        force2SetupCropView()
    }

    fun showCropDetails() {
        mFuncAndActionBarAnimHelper.interceptDirtyAnimation = true
        mFuncAndActionBarAnimHelper.showOrHideFuncAndBarView(false, object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator?) {
                showOrHideCropViewDetails(true)
                setupCropView()
            }
        })

    }

    private fun closeCropDetails() {
        mFuncAndActionBarAnimHelper.interceptDirtyAnimation = false
        showOrHideCropViewDetails(false)
        mFuncAndActionBarAnimHelper.showOrHideFuncAndBarView(true)
        closeCropView()
    }

    private fun setupCropView() {
        mCropSaveState?.let {
            val showingBitmap = mRootEditorDelegate.getDisplayBitmap()
            if (it.cropBitmap != showingBitmap) {
                it.cropBitmap?.recycle()
                it.cropBitmap = showingBitmap
            }
            mRootEditorDelegate.resetEditorSupportMatrix(Matrix())
            mRootEditorDelegate.setDisplayBitmap(it.originalBitmap)
            mRootEditorDelegate.getRooView().addOnLayoutChangeListener(LayerImageOnLayoutChangeListener())
            mCropDetailsView.setRestoreTextStatus(true)
        }
        mCropSaveState ?: let {
            force2SetupCropView()
        }
        mLayerComposite.handleEvent = false
    }

    private fun force2SetupCropView() {
        mRootEditorDelegate.force2Scale(mCropScaleRatio, false)
        val rect = mRootEditorDelegate.getDisplayingRect()
        mCropView.setupDrawingRect(rect!!)
        mCropView.updateCropMaxSize(rect.width(), rect.height())
        mCropDetailsView.setRestoreTextStatus(false)
    }

    inner class LayerImageOnLayoutChangeListener : android.view.View.OnLayoutChangeListener {
        override fun onLayoutChange(v: android.view.View?, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
            mCropSaveState?.let {
                mCropView.setupDrawingRect(it.cropRect)
                val matrix = Matrix()
                matrix.set(it.supportMatrix)
                //matrix.postConcat(Utils.getInvertMatrix(it.cropFitCenterMatrix))
                mRootEditorDelegate.setSupportMatrix(matrix)
            }
            mRootEditorDelegate.getRooView().removeOnLayoutChangeListener(this)
        }

    }

    private fun closeCropView() {
        mCropView.clearDrawingRect()
        mCropSaveState?.let {
            val state = it
            it.cropBitmap?.let {
                resetEditorSupportMatrix(state)
                logD1("closeCropView,reset cropBitmap")
                mRootEditorDelegate.setDisplayBitmap(it)
            }
        }
        mCropSaveState ?: let {
            mRootEditorDelegate.force2Scale(1 / mCropScaleRatio, false)
        }
        mLayerComposite.handleEvent = true
    }

    fun resetEditorSupportMatrix(state: CropSaveState) {
        /*convert rootLayer display matrix 2 supportMatrix*/
        val viewRect = RectF(0f, 0f, mRootEditorDelegate.getRooView().width.toFloat(), mRootEditorDelegate.getRooView().height.toFloat())
        val realMatrix = mapCropRect2FitCenter(state.cropRect, viewRect)
        state.cropFitCenterMatrix = realMatrix
        val editorMatrix = Matrix()
        editorMatrix.set(state.supportMatrix)
        editorMatrix.postConcat(realMatrix)
        mRootEditorDelegate.resetEditorSupportMatrix(editorMatrix)
    }

    private fun showOrHideCropViewDetails(show: Boolean) {
        mCropDetailsView.showOrHide(show)
    }

    private fun mapCropRect2FitCenter(lastCropRectF: RectF, viewRectF: RectF): Matrix {
        val matrix = Matrix()
        matrix.setRectToRect(lastCropRectF, viewRectF, Matrix.ScaleToFit.CENTER)
        return matrix
    }

    fun getSavedCropState(): CropSaveState? {
        mCropSaveState?.let {
            return it
        }
        return null
    }

    override fun saveLayerData(output: MutableMap<String, EditorCacheData>) {
        val tag = getLayerTag()
        mCropSaveState?.let {
            it.reset()
            mSavedStateMap.put(tag, it)
            output.put(tag, EditorCacheData(ArrayMap<String, CropSaveState>(mSavedStateMap)))
        }
        mCropSaveState ?: let {
            output.remove(tag)
        }
    }

    fun restoreCropData(originalBitmap: Bitmap): Bitmap {
        var cropBitmap: Bitmap? = null
        mCropSaveState?.let {
            it.originalBitmap = originalBitmap
            cropBitmap = getCropBitmap(it.cropRect, it.originalBitmap, it.supportMatrix, it.originalDisplayRectF)
            //resetEditorSupportMatrix(it)
        }
        if (cropBitmap == null) {
            mCropSaveState = null
            return originalBitmap
        }
        return cropBitmap!!
    }

    override fun restoreLayerData(input: MutableMap<String, EditorCacheData>) {
        val tag = getLayerTag()
        val cachedData = input[tag]
        cachedData?.let {
            val layerCache = it.layerCache as ArrayMap<String, CropSaveState>
            if (!layerCache.isEmpty) {
                val result = layerCache[tag]
                mCropSaveState = result?.deepCopy() as CropSaveState?
            }
        }
    }

    /*getCropBitmap....*/
    fun getCropBitmap(cropRect: RectF, source: Bitmap, supportMatrix: Matrix, displayRect: RectF?): Bitmap? {
        val rotated = getRotatedBitmap(source, supportMatrix)
        val realCropRect = calcCropRect(source.width, source.height, cropRect, supportMatrix, displayRect) ?: return null
        val cropped = Bitmap.createBitmap(
                rotated,
                realCropRect.left,
                realCropRect.top,
                realCropRect.width(),
                realCropRect.height(),
                null,
                false
        )
        if (rotated != cropped && rotated != source) {
            rotated.recycle()
        }
        return cropped
    }

    private fun calcCropRect(originalImageWidth: Int, originalImageHeight: Int, cropRect: RectF, supportMatrix: Matrix, displayRect: RectF?): Rect? {
        val mImageRect = displayRect ?: return null
        val mAngle = getRotateDegree(supportMatrix)
        val scaleToOriginal = getRotatedWidth(mAngle, originalImageWidth.toFloat(), originalImageHeight.toFloat()) / mImageRect.width()
        val offsetX = mImageRect.left * scaleToOriginal
        val offsetY = mImageRect.top * scaleToOriginal
        val left = Math.round(cropRect.left * scaleToOriginal - offsetX)
        val top = Math.round(cropRect.top * scaleToOriginal - offsetY)
        val right = Math.round(cropRect.right * scaleToOriginal - offsetX)
        val bottom = Math.round(cropRect.bottom * scaleToOriginal - offsetY)
        val imageW = Math.round(getRotatedWidth(mAngle, originalImageWidth.toFloat(), originalImageHeight.toFloat()))
        val imageH = Math.round(getRotatedHeight(mAngle, originalImageWidth.toFloat(), originalImageHeight.toFloat()))
        return Rect(Math.max(left, 0), Math.max(top, 0), Math.min(right, imageW),
                Math.min(bottom, imageH))
    }

    private fun getRotatedBitmap(bitmap: Bitmap, matrix: Matrix): Bitmap {
        val rotateMatrix = Matrix()
        rotateMatrix.setRotate(getRotateDegree(matrix), (bitmap.width / 2).toFloat(), (bitmap.height / 2).toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                rotateMatrix, true)
    }

    private fun getRotatedWidth(angle: Float, width: Float, height: Float): Float {
        return if (angle % 180 == 0f) width else height
    }

    private fun getRotatedHeight(angle: Float, width: Float, height: Float): Float {
        return if (angle % 180 == 0f) height else width
    }

    private fun getRotateDegree(matrix: Matrix) = Utils.getMatrixDegree(matrix)

}