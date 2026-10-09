package server;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Main {
    private static final String ADDRESS = "127.0.0.1";
    private static final int PORT = 23456;
    private static final Gson GSON = new Gson();
    private static final Path DB_FILE = Paths.get(System.getProperty("user.dir"),
            "src", "server", "data", "db.json");

    private static Database database;
    private static ServerSocket server;

    public static void main(String[] args) throws IOException, InterruptedException {
        database = new Database(DB_FILE);
        //syncronized thread
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        System.out.println("Server started!");

        try (ServerSocket serverSocket = new ServerSocket(PORT, 50, InetAddress.getByName(ADDRESS))) {
            server = serverSocket;
            // the main thread only accepts connections; each client is handled in an executor task
            while (true) {
                Socket socket;
                try {
                    socket = serverSocket.accept();
                } catch (SocketException e) {
                    break;  // the socket was closed by an "exit" request
                }
                executor.submit(() -> serve(socket));
            }
        }

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    // runs in an executor thread: read one request, send one response,close the connection
    private static void serve(Socket socket) {
        try (socket;
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            Request request = parse(input.readUTF());
            if (request != null && "exit".equals(request.type)) {
                output.writeUTF(GSON.toJson(Response.ok()));
                server.close();  // makes accept() in the main thread throw, which ends the loop
            } else {
                output.writeUTF(GSON.toJson(handle(request)));
            }
        } catch (IOException e) {
            e.fillInStackTrace();
        }
    }

    // turn the JSON text from the client back into a Request object(null if it isn't valid JSON)
    private static Request parse(String json) {
        try {
            //deserialization
            return GSON.fromJson(json, Request.class);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    private static Response handle(Request request) throws IOException {
        if (request == null || request.type == null || request.key == null) {
            return Response.error("Invalid request");
        }
        List<String> path = toPath(request.key);
        if (path.isEmpty()) {
            return Response.error("Invalid request");
        }
        return switch (request.type) {
            case "set" -> request.value != null
                    ? database.save(path, request.value)
                    : Response.error("No value");
            case "get" -> database.read(path);
            case "delete" -> database.delete(path);
            default -> Response.error("Unknown request type");
        };
    }

    // "person" -> [person],  ["person","rocket","launches"] -> [person, rocket, launches]
    private static List<String> toPath(JsonElement key) {
        List<String> path = new ArrayList<>();
        if (key.isJsonArray()) {
            for (JsonElement part : key.getAsJsonArray()) {
                path.add(part.getAsString());
            }
        } else {
            path.add(key.getAsString());
        }
        return path;
    }
}

// what the client sends: {"type":"set","key":["person","name"],"value":"Elon Musk"}
// key can be a string or an array, value can be any JSON (string, number, object, ...)
class Request {
    String type;
    JsonElement key;
    JsonElement value;
}

// what the server answers; null fields are left out of the JSON by Gson
class Response {
    String response;
    JsonElement value;
    String reason;

    static Response ok() {
        Response r = new Response();
        r.response = "OK";
        return r;
    }

    static Response ok(JsonElement value) {
        Response r = ok();
        r.value = value;
        return r;
    }

    static Response error(String reason) {
        Response r = new Response();
        r.response = "ERROR";
        r.reason = reason;
        return r;
    }
}

// the database lives in db.json; many threads may read it at once, but only one may write
class Database {
    private static final Gson GSON = new Gson();

    private final Path file;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();

    public Database(Path file) throws IOException {
        this.file = file;
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{}");
        }
    }

    // SET: walk down the path, creating any objects that are missing,then put the value at the end
    // ["person1","inside1","inside2"] creates "person1" and "inside1" if they aren't there yet
    public Response save(List<String> path, JsonElement value) throws IOException {
        writeLock.lock();
        try {
            JsonObject records = load();
            JsonObject current = records;
            for (String step : path.subList(0, path.size() - 1)) {
                JsonElement next = current.get(step);
                if (next == null || !next.isJsonObject()) {
                    next = new JsonObject();
                    current.add(step, next);
                }
                current = next.getAsJsonObject();
            }
            current.add(last(path), value);
            store(records);
            return Response.ok();
        } finally {
            writeLock.unlock();
        }
    }

    // GET: walk down the whole path and return whatever is there (a string, an object, ...)
    public Response read(List<String> path) throws IOException {
        readLock.lock();
        try {
            JsonObject parent = findParent(load(), path);
            JsonElement value = parent == null ? null : parent.get(last(path));
            return value == null ? Response.error("No such key") : Response.ok(value);
        } finally {
            readLock.unlock();
        }
    }

    // DELETE: remove only the last part of the path; the parent objects stay
    // (the file is only rewritten when something was actually removed)
    public Response delete(List<String> path) throws IOException {
        writeLock.lock();
        try {
            JsonObject records = load();
            JsonObject parent = findParent(records, path);
            if (parent == null || parent.remove(last(path)) == null) {
                return Response.error("No such key");
            }
            store(records);
            return Response.ok();
        } finally {
            writeLock.unlock();
        }
    }

    private static JsonObject findParent(JsonObject records, List<String> path) {
        JsonObject current = records;
        // follow every step of the path except the last one;
        for (String step : path.subList(0, path.size() - 1)) {
            //it looks up the value with current.get(step)
            JsonElement next = current.get(step);
            //null if some step is missing or isn't an object
            if (next == null || !next.isJsonObject()) {
                return null;
            }
            current = next.getAsJsonObject();
        }
        //it returns the object it ended up in.
        return current;
    }

    private static String last(List<String> path) {
        return path.get(path.size() - 1);
    }

    private JsonObject load() throws IOException {
        //Map<String, String> records = GSON.fromJson(Files.readString(file), MAP_TYPE);
        //return records == null ? new HashMap<>() : records;  // an empty file gives null
        String json = Files.readString(file);//read the file
        if (json.isBlank()) {
            return new JsonObject();  // empty file -> empty database
        }
        //parse String to  Json object
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void store(JsonObject records) throws IOException {
        //if the parent folder doesn’t exist, the write fails.
        //GSON.toJson(records) serializes a List of objects into a JSON string.
        Files.writeString(file, GSON.toJson(records));
    }
}
