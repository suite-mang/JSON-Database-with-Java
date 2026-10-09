package client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.google.gson.Gson;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static final String ADDRESS = "127.0.0.1";
    private static final int PORT = 23456;
    private static final Path DATA_DIR = Paths.get(System.getProperty("user.dir"),
            "src", "client", "data");

    public static void main(String[] args) throws IOException {
        // JCommander fills the @Parameter fields of the request from the command-line arguments
        Request request = new Request();
        JCommander.newBuilder()
                .addObject(request)
                .build()
                .parse(args);

        System.out.println("Client started!");
        String json;
        if (request.inputFile != null) {
            // -in setFile.json: send the request exactly as it is written in src/client/data/setFile.json
            // (this is how nested keys like ["person","name"] and object values get sent)
            json = Files.readString(DATA_DIR.resolve(request.inputFile)).trim();
        } else {
            // Gson skips null fields, so "-t get -k 1" becomes {"type":"get","key":"1"}
            json = new Gson().toJson(request);
        }

        try (Socket socket = new Socket(InetAddress.getByName(ADDRESS), PORT);
             DataInputStream input = new DataInputStream(socket.getInputStream());
             DataOutputStream output = new DataOutputStream(socket.getOutputStream())) {
            output.writeUTF(json);
            System.out.println("Sent: " + json);
            System.out.println("Received: " + input.readUTF());
        }
    }
}

// field order matters: Gson writes the fields in the order they are declared
// key and value stay Strings: on the command line they are always plain text,
// JCommander can't turn an argument into a JsonElement
class Request {
    @Parameter(names = "-t", description = "Type of the request: get, set, delete or exit")
    String type;

    @Parameter(names = "-k", description = "Key of the record")
    String key;

    @Parameter(names = "-v", description = "Value to save (only for set)")
    String value;

    // transient: Gson must not put this into the JSON that is sent to the server
    @Parameter(names = "-in", description = "Name of a file in src/client/data that holds the request")
    transient String inputFile;
}
