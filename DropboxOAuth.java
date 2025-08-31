
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.awt.Desktop;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class DropboxOAuth {

    private static final String AUTH_URL = "https://www.dropbox.com/oauth2/authorize";
    private static final String TOKEN_URL = "https://api.dropboxapi.com/oauth2/token";
    private static final String TEAM_INFO_URL = "https://api.dropboxapi.com/2/team/get_info";
    private static final String USER_INFO_URL = "https://api.dropboxapi.com/2/users/get_current_account";

    public static void main(String[] args) throws Exception {
        var clientId = System.getenv("DROPBOX_CLIENT_ID");
        var clientSecret = System.getenv("DROPBOX_CLIENT_SECRET");
        var redirectUri = System.getenv("DROPBOX_REDIRECT_URI");
        var scopes = System.getenv().getOrDefault("DROPBOX_SCOPES", "team_info.read");

        if (clientId == null || clientSecret == null || redirectUri == null) {
            System.err.println("Please set DROPBOX_CLIENT_ID, DROPBOX_CLIENT_SECRET, DROPBOX_REDIRECT_URI");
            System.exit(1);
        }

        var redirect = new URI(redirectUri);
        int port = redirect.getPort();
        var holder = new Object() { String code = null; };

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(redirect.getPath(), (HttpExchange exchange) -> {
            var query = exchange.getRequestURI().getQuery();
            var params = parseQuery(query);
            holder.code = params.get("code");
            var body = "Authentication completed successfully. You may close this window.";
            exchange.sendResponseHeaders(200, body.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body.getBytes());
            }
        });
        server.start();

        var state = UUID.randomUUID().toString();
        var authUrl = AUTH_URL +
                "?response_type=code" +
                "&client_id=" + url(clientId) +
                "&redirect_uri=" + url(redirectUri) +
                "&token_access_type=offline" +
                "&scope=" + url(scopes) +
                "&state=" + state;

        System.out.println("Open this following URL in your browser to authenticate:");
        System.out.println();
        System.out.println(authUrl);
        System.out.println();

        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(new URI(authUrl));
        }

        while (holder.code == null) {
            Thread.sleep(500);
        }
        server.stop(0);

        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        var body = "code=" + url(holder.code)
                + "&grant_type=authorization_code"
                + "&client_id=" + url(clientId)
                + "&client_secret=" + url(clientSecret)
                + "&redirect_uri=" + url(redirectUri);

        var tokenReq = HttpRequest.newBuilder()
                .uri(URI.create(TOKEN_URL))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        var tokenRes = http.send(tokenReq, HttpResponse.BodyHandlers.ofString());

        System.out.println();
        System.out.println("Token response: " + tokenRes.body());

        var accessToken = extractJson(tokenRes.body(), "access_token");

        var apiUrl = scopes.contains("team_info.read") ? TEAM_INFO_URL : USER_INFO_URL;
        
        System.out.println();
        System.out.println("apiUrl: " + apiUrl + "\n apiScope: " + scopes);
        System.out.println();

        var apiReq = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Authorization", "Bearer " + accessToken)
                // .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var apiRes = http.send(apiReq, HttpResponse.BodyHandlers.ofString());
        System.out.println("API response: " + apiRes.body());
    }

    private static String url(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String q) {
        var map = new HashMap<String, String>();
        if (q == null) return map;
        for (var s : q.split("&")) {
            var kv = s.split("=", 2);
            var k = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            var v = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(k, v);
        }
        return map;
    }

    private static String extractJson(String json, String key) {
        var i = json.indexOf("\"" + key + "\"");
        if (i < 0) return null;
        var start = json.indexOf('"', json.indexOf(':', i) + 1) + 1;
        var end = json.indexOf('"', start);
        if (start < 1 || end < 0) return null;
        return json.substring(start, end);
    }
}
