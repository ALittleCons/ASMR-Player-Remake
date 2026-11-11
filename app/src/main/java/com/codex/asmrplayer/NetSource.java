package com.codex.asmrplayer;

import org.json.JSONException;
import org.json.JSONObject;

/** 网络路径源（SMB / FTP）配置。 */
class NetSource {
    String id;      // 稳定标识，用于构造 net://<id>/<path> 形式的文件 uri
    String type;    // "smb" 或 "ftp"
    String host;
    int port;       // 0 表示按类型取默认端口
    String path;    // smb: "共享名/子目录"；ftp: 基础目录（可空）
    String user;
    String pass;
    String name;    // 显示名

    String defaultPort() {
        return "ftp".equals(type) ? "21" : "445";
    }

    String displayTarget() {
        return type + "://" + host + (port > 0 ? ":" + port : "") + "/" + (path == null ? "" : path);
    }

    JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("type", type);
        object.put("host", host);
        object.put("port", port);
        object.put("path", path);
        object.put("user", user);
        object.put("pass", pass);
        object.put("name", name);
        return object;
    }

    static NetSource fromJson(JSONObject object) {
        NetSource source = new NetSource();
        source.id = object.optString("id", "");
        source.type = "ftp".equals(object.optString("type")) ? "ftp" : "smb";
        source.host = object.optString("host", "");
        source.port = object.optInt("port", 0);
        source.path = object.optString("path", "");
        source.user = object.optString("user", "");
        source.pass = object.optString("pass", "");
        source.name = object.optString("name", "");
        return source;
    }
}
