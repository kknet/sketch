/*
 * Copyright (C) 2016 Peng fei Pan <sky@xiaopan.me>
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

package me.xiaopan.sketch.feature.large;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

import me.xiaopan.sketch.Sketch;
import me.xiaopan.sketch.util.SketchUtils;

/**
 * 大图片查看器
 */
// TODO: 16/8/29 加上旋转之后，不知道会有什么异常问题
public class LargeImageViewer {
    private static final String NAME = "LargeImageViewer";

    private Context context;
    private Callback callback;

    private boolean showTileRect;

    private float zoomScale;
    private float lastZoomScale;
    private Paint drawTilePaint;
    private Paint drawTileRectPaint;
    private Paint loadingTileRectPaint;
    private Matrix matrix;

    private boolean running;
    private Rect visibleRect = new Rect();
    private UpdateParams waitUpdateParams;
    private TileManager tileManager;
    private TileDecodeExecutor executor;
    private String imageUri;

    public LargeImageViewer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;

        this.matrix = new Matrix();
        this.executor = new TileDecodeExecutor(new ExecutorCallback());
        this.drawTilePaint = new Paint();
        this.tileManager = new TileManager(context.getApplicationContext(), this);
    }

    public void draw(Canvas canvas) {
        List<Tile> tileList = tileManager.getTileList();
        if (tileList != null && tileList.size() > 0) {
            int saveCount = canvas.save();
            canvas.concat(matrix);

            for (Tile tile : tileList) {
                if (!tile.isEmpty()) {
                    canvas.drawBitmap(tile.bitmap, tile.bitmapDrawSrcRect, tile.drawRect, drawTilePaint);
                    if (showTileRect) {
                        if (drawTileRectPaint == null) {
                            drawTileRectPaint = new Paint();
                            drawTileRectPaint.setColor(Color.parseColor("#88FF0000"));
                        }
                        canvas.drawRect(tile.drawRect, drawTileRectPaint);
                    }
                } else if (!tile.isDecodeParamEmpty()) {
                    if (showTileRect) {
                        if (loadingTileRectPaint == null) {
                            loadingTileRectPaint = new Paint();
                            loadingTileRectPaint.setColor(Color.parseColor("#880000FF"));
                        }
                        canvas.drawRect(tile.drawRect, loadingTileRectPaint);
                    }
                }
            }

            canvas.restoreToCount(saveCount);
        }
    }

    /**
     * 设置新的图片
     */
    public void setImage(String imageUri) {
        clean("setImage");

        this.imageUri = imageUri;
        if (!TextUtils.isEmpty(imageUri)) {
            running = true;
            executor.initDecoder(imageUri);
        } else {
            running = false;
            executor.initDecoder(null);
        }
    }

    /**
     * 更新
     */
    public void update(UpdateParams updateParams) {
        // 不可用，也没有初始化就直接结束
        if (!isReady() && !isInitializing()) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". not available. " + imageUri);
            }
            return;
        }

        // 传进来的参数不能用就什么也不显示
        if (updateParams.isEmpty()) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". update params is empty. update. " + updateParams.getInfo() + ". " + imageUri);
            }
            clean("update param is empty");
            return;
        }

        // 如果正在初始化就就缓存当前更新参数
        if (!isReady() && isInitializing()) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". initializing. update. " + imageUri);
            }
            if (waitUpdateParams == null) {
                waitUpdateParams = new UpdateParams();
            }
            waitUpdateParams.set(updateParams);
            return;
        }

        // 过滤掉重复的刷新
        if (visibleRect.equals(updateParams.visibleRect)) {
            if (Sketch.isDebugMode()) {
                Log.w(Sketch.TAG, NAME + ". visible rect no changed. update. visibleRect=" + updateParams.visibleRect.toShortString() + ", oldVisibleRect=" + visibleRect.toShortString() + ". " + imageUri);
            }
            return;
        }
        visibleRect.set(updateParams.visibleRect);

        // 如果当前完整显示预览图的话就清空什么也不显示
        int visibleWidth = updateParams.visibleRect.width();
        int visibleHeight = updateParams.visibleRect.height();
        if (visibleWidth == updateParams.previewDrawableWidth && visibleHeight == updateParams.previewDrawableHeight) {
            if (Sketch.isDebugMode()) {
                Log.d(Sketch.TAG, NAME + ". full display. update. " + imageUri);
            }
            clean("full display");
            return;
        }

        // 取消旧的任务并更新Matrix
        lastZoomScale = zoomScale;
        matrix.set(updateParams.drawMatrix);
        zoomScale = SketchUtils.formatFloat(SketchUtils.getMatrixScale(matrix), 2);

        callback.invalidate();

        tileManager.update(updateParams.visibleRect,
                updateParams.imageViewWidth, updateParams.imageViewHeight,
                executor.getDecoder().getImageWidth(), executor.getDecoder().getImageHeight(),
                updateParams.previewDrawableWidth, updateParams.previewDrawableHeight);
    }

    /**
     * 清理资源（不影响继续使用）
     */
    private void clean(String why) {
        executor.cleanDecode(why);

        if (waitUpdateParams != null) {
            waitUpdateParams.reset();
        }
        matrix.reset();
        lastZoomScale = 0;
        zoomScale = 0;

        tileManager.clean(why);

        callback.invalidate();
    }

    /**
     * 回收资源（回收后需要重新setImage()才能使用）
     */
    public void recycle(String why) {
        running = false;
        clean(why);
        executor.recycle(why);
        tileManager.recycle(why);
    }

    public void invalidateView(){
        callback.invalidate();
    }

    /**
     * 准备好了？
     */
    public boolean isReady() {
        return running && executor.isReady();
    }

    /**
     * 初始化中？
     */
    public boolean isInitializing() {
        return running && executor.isInitializing();
    }

    /**
     * 是否显示碎片的范围（红色表示已加载，蓝色表示正在加载）
     */
    public boolean isShowTileRect() {
        return showTileRect;
    }

    /**
     * 设置是否显示碎片的范围（红色表示已加载，蓝色表示正在加载）
     */
    @SuppressWarnings("unused")
    public void setShowTileRect(boolean showTileRect) {
        this.showTileRect = showTileRect;
        callback.invalidate();
    }

    /**
     * 获取当前缩放比例
     */
    public float getZoomScale() {
        return zoomScale;
    }

    /**
     * 获取上次的缩放比例
     */
    public float getLastZoomScale() {
        return lastZoomScale;
    }

    public TileDecodeExecutor getExecutor() {
        return executor;
    }

    public TileManager getTileManager() {
        return tileManager;
    }

    public interface Callback {
        void invalidate();
        void updateMatrix();
    }

    public interface OnTileChangedListener {
        void onTileChanged(LargeImageViewer largeImageViewer);
    }

    private class ExecutorCallback implements TileDecodeExecutor.Callback {

        @Override
        public Context getContext() {
            return context;
        }

        @Override
        public void onInitCompleted() {
            if (!running) {
                if (Sketch.isDebugMode()) {
                    Log.w(Sketch.TAG, NAME + ". stop running. initCompleted");
                }
                return;
            }

            if (waitUpdateParams != null && !waitUpdateParams.isEmpty()) {
                if (Sketch.isDebugMode()) {
                    Log.d(Sketch.TAG, NAME + ". initCompleted. Dealing waiting update params");
                }

                UpdateParams updateParams = new UpdateParams();
                updateParams.set(waitUpdateParams);
                waitUpdateParams.reset();

                update(updateParams);
            }

            callback.updateMatrix();
        }

        @Override
        public void onInitFailed(Exception e) {
            if (!running) {
                if (Sketch.isDebugMode()) {
                    Log.w(Sketch.TAG, NAME + ". stop running. initFailed");
                }
                return;
            }

            if (Sketch.isDebugMode()) {
                Log.d(Sketch.TAG, NAME + ". initFailed");
            }
        }

        @Override
        public void onDecodeCompleted(Tile tile, Bitmap bitmap) {
            if (!running) {
                if (Sketch.isDebugMode()) {
                    Log.w(Sketch.TAG, NAME + ". stop running. decodeCompleted. tile=" + tile.getInfo());
                }
                bitmap.recycle();
                return;
            }

            tileManager.decodeCompleted(tile, bitmap);
        }

        @Override
        public void onDecodeFailed(Tile tile, DecodeHandler.DecodeFailedException exception) {
            if (!running) {
                if (Sketch.isDebugMode()) {
                    Log.w(Sketch.TAG, NAME + ". stop running. decodeFailed. tile=" + tile.getInfo());
                }
                return;
            }

            tileManager.decodeFailed(tile, exception);
        }
    }
}