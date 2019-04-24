package com.rayworks.droidcast;

import android.graphics.Bitmap;
import android.graphics.Point;
import android.os.Looper;
import android.os.Process;
import android.support.annotation.Nullable;
import android.support.v4.util.Pair;
import android.text.TextUtils;

import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.http.Multimap;
import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.koushikdutta.async.http.server.AsyncHttpServerResponse;
import com.koushikdutta.async.http.server.HttpServerRequestCallback;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Created by seanzhou on 3/14/17. */
public class Main {
    private static final String IMAGE_JPEG = "image/jpeg";
    private static final String IMAGE_WEBP = "image/webp";
    private static final String IMAGE_PNG = "image/png";
    private static final String WIDTH = "width";
    private static final String HEIGHT = "height";
    private static final String FORMAT = "format";

    private static Looper looper;
    private static int width = 0;
    private static int height = 0;

    private static DisplayUtil displayUtil;

    public static void main(String[] args) {
        AsyncHttpServer httpServer =
                new AsyncHttpServer() {
                    @Override
                    protected boolean onRequest(
                            AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
                        return super.onRequest(request, response);
                    }
                };

        Looper.prepare();

        looper = Looper.myLooper();
        System.out.println(">>> DroidCast main entry");

        displayUtil = new DisplayUtil();

        AsyncServer server = new AsyncServer();
        httpServer.get("/screenshot", new AnyRequestCallback());

        httpServer.websocket(
                "/src",
                new AsyncHttpServer.WebSocketRequestCallback() {
                    @Override
                    public void onConnected(WebSocket webSocket, AsyncHttpServerRequest request) {

                        Point displaySize = displayUtil.getCurrentDisplaySize();

                        int width = 1080;
                        int height = 1920;
                        if (displaySize != null) {
                            width = displaySize.x;
                            height = displaySize.y;
                        }

                        displayUtil.setRotateListener(
                                new DisplayUtil.RotateListener() {
                                    @Override
                                    public void onRotate(int rotate) {
                                        Point displaySize = displayUtil.getCurrentDisplaySize();

                                        int width = 1080;
                                        int height = 1920;

                                        if (displaySize != null) {
                                            width = displaySize.x;
                                            height = displaySize.y;
                                        }

                                        System.out.println(">>> rotate to " + rotate);
                                        sendScreenshotData(webSocket, width, height, rotate);
                                    }
                                });

                        sendScreenshotData(webSocket, width, height, 0);
                    }
                });

        httpServer.listen(server, 53516);

        Looper.loop();
    }

    private static void sendScreenshotData(
            WebSocket webSocket, int width, int height, int rotation) {

        int w = width;
        int h = height;
        if (rotation % 2 != 0) {
            w = height;
            h = width;
        }
        try {
            JSONObject object = new JSONObject();
            object.put("width", w);
            object.put("height", h);

            webSocket.send(object.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }

        try {
            byte[] inBytes =
                    getScreenImageInBytes(Bitmap.CompressFormat.JPEG, width, height, rotation);
            webSocket.send(inBytes);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static byte[] getScreenImageInBytes(
            Bitmap.CompressFormat compressFormat, int destWidth, int destHeight, int screenRotation)
            throws IOException {
        Bitmap bitmap = ScreenCaptor.screenshot(destWidth, destHeight);

        if (bitmap == null) {
            System.out.println(
                    String.format(
                            Locale.ENGLISH,
                            ">>> failed to generate image with resolution %d:%d",
                            Main.width,
                            Main.height));

            destWidth /= 2;
            destHeight /= 2;

            bitmap = ScreenCaptor.screenshot(destWidth, destHeight);
        }

        System.out.println(
                String.format(
                        Locale.ENGLISH,
                        "Bitmap generated with resolution %d:%d, process id %d | thread id %d",
                        destWidth,
                        destHeight,
                        Process.myPid(),
                        Process.myTid()));

        if (screenRotation != 0) {
            switch (screenRotation) {
                case 1: // 90 degree rotation (counter-clockwise)
                    bitmap = displayUtil.rotateBitmap(bitmap, -90f);
                    break;
                case 3: // 270 degree rotation
                    bitmap = displayUtil.rotateBitmap(bitmap, 90f);
                    break;
                case 2: // 180 degree rotation
                    bitmap = displayUtil.rotateBitmap(bitmap, 180f);
                    break;
            }
        }

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        bitmap.compress(compressFormat, 100, bout);
        bout.flush();

        // "Make sure to call Bitmap.recycle() as soon as possible, once its content is not
        // needed anymore."
        bitmap.recycle();

        return bout.toByteArray();
    }

    static class AnyRequestCallback implements HttpServerRequestCallback {
        private Pair<Bitmap.CompressFormat, String> mapRequestFormatInfo(ImageFormat imageFormat) {
            Bitmap.CompressFormat compressFormat;
            String contentType;

            switch (imageFormat) {
                case JPEG:
                    compressFormat = Bitmap.CompressFormat.JPEG;
                    contentType = IMAGE_JPEG;
                    break;
                case PNG:
                    compressFormat = Bitmap.CompressFormat.PNG;
                    contentType = IMAGE_PNG;
                    break;
                case WEBP:
                    compressFormat = Bitmap.CompressFormat.WEBP;
                    contentType = IMAGE_WEBP;
                    break;

                default:
                    throw new UnsupportedOperationException("Unsupported image format detected");
            }

            return new Pair<>(compressFormat, contentType);
        }

        @Nullable
        private Pair<Bitmap.CompressFormat, String> getImageFormatInfo(String reqFormat) {

            ImageFormat format = ImageFormat.JPEG;

            if (!TextUtils.isEmpty(reqFormat)) {
                ImageFormat imageFormat = ImageFormat.resolveFormat(reqFormat);
                if (ImageFormat.UNKNOWN.equals(imageFormat)) {
                    return null;
                } else {
                    // default format
                    format = imageFormat;
                }
            }

            return mapRequestFormatInfo(format);
        }

        @Override
        public void onRequest(AsyncHttpServerRequest request, AsyncHttpServerResponse response) {
            try {
                Multimap pairs = request.getQuery();

                String width = pairs.getString(WIDTH);
                String height = pairs.getString(HEIGHT);
                String reqFormat = pairs.getString(FORMAT);

                Pair<Bitmap.CompressFormat, String> formatInfo = getImageFormatInfo(reqFormat);

                if (formatInfo == null) {
                    response.code(400);
                    response.send(
                            String.format(
                                    Locale.ENGLISH, "Unsupported image format : %s", reqFormat));

                    return;
                }

                if (!TextUtils.isEmpty(width) && !TextUtils.isEmpty(height)) {
                    if (TextUtils.isDigitsOnly(width) && TextUtils.isDigitsOnly(height)) {
                        Main.width = Integer.parseInt(width);
                        Main.height = Integer.parseInt(height);
                    }
                }

                if (Main.width == 0 || Main.height == 0) {
                    // dimension initialization
                    Point point = displayUtil.getCurrentDisplaySize();

                    if (point != null && point.x > 0 && point.y > 0) {
                        Main.width = point.x;
                        Main.height = point.y;
                    } else {
                        Main.width = 480;
                        Main.height = 800;
                    }
                }

                int destWidth = Main.width;
                int destHeight = Main.height;
                int screenRotation = displayUtil.getScreenRotation();

                byte[] bytes =
                        getScreenImageInBytes(
                                formatInfo.first, destWidth, destHeight, screenRotation);

                response.send(formatInfo.second, bytes);

                //                String encode = Base64.encodeToString(bytes, Base64.DEFAULT);
                //                System.out.println("================== encoded started
                // ==================");
                //                System.out.println(encode);
                //                System.out.println("================== encoded end
                // ==================");

            } catch (Exception e) {
                response.code(500);
                response.send(e.toString());
            }
        }
    }
}
