package com.openclaw.a11y;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenClaw Accessibility Bridge
 * 
 * Exposes the live UI tree over HTTP (localhost:7333) so an AI agent
 * can read and interact with the device in ~50ms instead of the 3-5s
 * adb screencap + uiautomator dump cycle.
 *
 * Endpoints:
 *   GET  /screen          → full UI tree as JSON
 *   GET  /screen?compact  → compact text-only summary
 *   POST /click           → click by text, id, or description
 *                           body: {"text":"Send"} or {"id":"com.app:id/btn"} or {"desc":"Back"}
 *   POST /tap             → tap by coordinates  body: {"x":540,"y":960}
 *   GET  /ping            → health check
 */
public class ClawAccessibilityService extends AccessibilityService {
    private static final String TAG = "ClawA11y";
    private static final int PORT = 7333;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile boolean running = false;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "Accessibility service connected, starting HTTP server on port " + PORT);
        startServer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We don't need to react to individual events;
        // we read the tree on-demand via getRootInActiveWindow()
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        Log.i(TAG, "Accessibility service destroyed");
    }

    // ── HTTP Server ──────────────────────────────────────────────

    private void startServer() {
        running = true;
        serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "HTTP server listening on :" + PORT);
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        handleClient(client);
                    } catch (IOException e) {
                        if (running) Log.e(TAG, "Accept error", e);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Could not start server on port " + PORT, e);
            }
        }, "claw-http");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void handleClient(Socket client) {
        new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
                 OutputStream out = client.getOutputStream()) {

                String requestLine = in.readLine();
                if (requestLine == null) return;

                // Read headers
                int contentLength = 0;
                String line;
                while ((line = in.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.substring(15).trim());
                    }
                }

                // Read body if present
                String body = "";
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int read = in.read(buf, 0, contentLength);
                    body = new String(buf, 0, read);
                }

                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts.length > 1 ? parts[1] : "/";

                String response;
                int status = 200;

                try {
                    if (path.equals("/ping")) {
                        response = "{\"status\":\"ok\",\"service\":\"openclaw-a11y\"}";
                    } else if (path.startsWith("/screen")) {
                        boolean compact = path.contains("compact");
                        response = getScreenJson(compact);
                    } else if (path.equals("/click") && method.equals("POST")) {
                        response = handleClick(body);
                    } else if (path.equals("/tap") && method.equals("POST")) {
                        response = handleTap(body);
                    } else {
                        status = 404;
                        response = "{\"error\":\"not found\",\"endpoints\":[\"/screen\",\"/click\",\"/tap\",\"/ping\"]}";
                    }
                } catch (Exception e) {
                    status = 500;
                    response = "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
                }

                String httpResponse = "HTTP/1.1 " + status + " OK\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n" +
                        "Content-Length: " + response.getBytes("UTF-8").length + "\r\n" +
                        "\r\n" + response;
                out.write(httpResponse.getBytes("UTF-8"));
                out.flush();

            } catch (Exception e) {
                Log.e(TAG, "Client handler error", e);
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }, "claw-req").start();
    }

    // ── Screen Reading ───────────────────────────────────────────

    private String getScreenJson(boolean compact) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return "{\"error\":\"no active window\",\"nodes\":[]}";
        }
        try {
            JSONObject result = new JSONObject();
            result.put("package", root.getPackageName());
            result.put("timestamp", System.currentTimeMillis());
            JSONArray nodes = new JSONArray();
            traverseNode(root, nodes, 0, compact);
            result.put("nodes", nodes);
            result.put("count", nodes.length());
            return result.toString();
        } catch (Exception e) {
            return "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}";
        } finally {
            root.recycle();
        }
    }

    private void traverseNode(AccessibilityNodeInfo node, JSONArray nodes, int depth, boolean compact) {
        if (node == null) return;
        try {
            String text = node.getText() != null ? node.getText().toString() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
            String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            String cls = node.getClassName() != null ? node.getClassName().toString() : "";
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            boolean hasContent = !text.isEmpty() || !desc.isEmpty() || node.isClickable()
                    || node.isEditable() || node.isScrollable() || node.isCheckable();

            if (!compact || hasContent) {
                JSONObject obj = new JSONObject();
                if (!text.isEmpty()) obj.put("text", text);
                if (!desc.isEmpty()) obj.put("desc", desc);
                if (!id.isEmpty()) obj.put("id", id);
                if (!compact) obj.put("cls", cls);
                obj.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
                if (node.isClickable()) obj.put("click", true);
                if (node.isEditable()) obj.put("edit", true);
                if (node.isScrollable()) obj.put("scroll", true);
                if (node.isCheckable()) obj.put("checkable", true);
                if (node.isChecked()) obj.put("checked", true);
                if (node.isFocused()) obj.put("focused", true);
                if (node.isSelected()) obj.put("selected", true);
                if (!compact) obj.put("depth", depth);
                nodes.put(obj);
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    traverseNode(child, nodes, depth + 1, compact);
                    child.recycle();
                }
            }
        } catch (Exception e) {
            // Skip problematic nodes
        }
    }

    // ── Click Handling ───────────────────────────────────────────

    private String handleClick(String body) throws Exception {
        JSONObject req = new JSONObject(body);
        String targetText = req.optString("text", "");
        String targetId = req.optString("id", "");
        String targetDesc = req.optString("desc", "");

        if (targetText.isEmpty() && targetId.isEmpty() && targetDesc.isEmpty()) {
            return "{\"error\":\"provide 'text', 'id', or 'desc'\"}";
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "{\"error\":\"no active window\"}";

        try {
            AccessibilityNodeInfo target = findNode(root, targetText, targetId, targetDesc);
            if (target == null) {
                return "{\"error\":\"element not found\",\"text\":\"" + escapeJson(targetText) +
                        "\",\"id\":\"" + escapeJson(targetId) +
                        "\",\"desc\":\"" + escapeJson(targetDesc) + "\"}";
            }

            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            int x = bounds.centerX();
            int y = bounds.centerY();

            // Try AccessibilityNodeInfo.performAction first (more reliable)
            boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            
            if (!clicked) {
                // Fall back to gesture-based tap
                clicked = performTapGesture(x, y);
            }

            target.recycle();

            JSONObject result = new JSONObject();
            result.put("clicked", clicked);
            result.put("x", x);
            result.put("y", y);
            if (!targetText.isEmpty()) result.put("matchedText", targetText);
            if (!targetId.isEmpty()) result.put("matchedId", targetId);
            if (!targetDesc.isEmpty()) result.put("matchedDesc", targetDesc);
            return result.toString();
        } finally {
            root.recycle();
        }
    }

    private String handleTap(String body) throws Exception {
        JSONObject req = new JSONObject(body);
        int x = req.getInt("x");
        int y = req.getInt("y");
        boolean tapped = performTapGesture(x, y);
        return "{\"tapped\":" + tapped + ",\"x\":" + x + ",\"y\":" + y + "}";
    }

    private boolean performTapGesture(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        return dispatchGesture(builder.build(), null, null);
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, String text, String id, String desc) {
        if (root == null) return null;

        // Check current node
        if (matches(root, text, id, desc)) return root;

        // Recurse children
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findNode(child, text, id, desc);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    private boolean matches(AccessibilityNodeInfo node, String text, String id, String desc) {
        if (!text.isEmpty()) {
            String nodeText = node.getText() != null ? node.getText().toString() : "";
            if (nodeText.toLowerCase().contains(text.toLowerCase())) return true;
        }
        if (!id.isEmpty()) {
            String nodeId = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            if (nodeId.contains(id)) return true;
        }
        if (!desc.isEmpty()) {
            String nodeDesc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
            if (nodeDesc.toLowerCase().contains(desc.toLowerCase())) return true;
        }
        return false;
    }

    // ── Utilities ────────────────────────────────────────────────

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
