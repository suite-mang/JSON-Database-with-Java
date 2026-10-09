# JSON Database with Java

A client-server JSON database written in Java. The server keeps its data in a JSON file
and can handle several clients at the same time. The client sends requests from command-line arguments
or from a JSON file. Values can be any JSON (strings, numbers or nested objects), and you can read or
change a single field inside a nested object by giving a path like `["person", "car", "model"]`.

This is the [Hyperskill](https://hyperskill.org/projects/65) project *JSON Database with Java*,
completed through all six stages.

## Features

- **Client-server over TCP sockets.** The server listens on `127.0.0.1:23456`, and each client sends
  one request, gets one response and exits.
- **JSON protocol.** Requests and responses are JSON, handled with [Gson](https://github.com/google/gson).
- **Data saved in a file.** Everything is stored in `src/server/data/db.json`, so the data survives a
  server restart. The file is only rewritten after a `set` or a successful `delete`.
- **Several clients at once.** The main thread only accepts connections. Each request is handled
  by a task in an `ExecutorService`.
- **Safe file access.** A `ReentrantReadWriteLock` lets many `get` requests read at the same time,
  while `set` and `delete` write one at a time.
- **Nested keys.** A key can be a string (`"person"`) or a path (`["person", "rocket", "launches"]`).
  - `set` creates any objects missing along the path.
  - `delete` removes only the last part of the path.
- **Command-line parsing** with [JCommander](https://jcommander.org/).

## Project structure

```
JSON Database with Java/
├── <stage folders>/          task descriptions of the six stages
└── task/
    ├── src/
    │   ├── client/
    │   │   ├── Main.java     client: parses arguments, sends a request, prints the response
    │   │   └── data/         JSON request files used with -in
    │   └── server/
    │       ├── Main.java     server: socket loop, executor, request handling, Database
    │       └── data/db.json  database file (created automatically)
    └── test/                 Hyperskill stage tests
```

## Requirements

- JDK 17 or newer
- Gradle (the wrapper `gradlew` is included, so no install is needed)

Dependencies (downloaded by Gradle): `com.beust:jcommander:1.78` and `com.google.code.gson:gson:2.8.6`.

## How to run

Open the project in IntelliJ IDEA, or run it from a terminal. Start the **server first**, then run one or
more clients. Use the `task` folder as the working directory, because the data paths are built from it.

**Server**
```
server.Main
```

**Client, with arguments**
```
client.Main -t <type> -k <key> [-v <value>]
client.Main -in <file name in src/client/data>
```

| Argument | Meaning |
|----------|---------|
| `-t`     | request type: `get`, `set`, `delete` or `exit` |
| `-k`     | key |
| `-v`     | value (only for `set`) |
| `-in`    | read the whole request from a JSON file in `src/client/data` |

`-t exit` stops the server.

## Protocol

| Request | Response |
|---------|----------|
| `{"type":"set","key":"name","value":"Sorabh"}` | `{"response":"OK"}` |
| `{"type":"get","key":"name"}` | `{"response":"OK","value":"Sorabh"}` |
| `{"type":"get","key":"missing"}` | `{"response":"ERROR","reason":"No such key"}` |
| `{"type":"delete","key":["person","car","year"]}` | `{"response":"OK"}` |
| `{"type":"exit"}` | `{"response":"OK"}` (the server then shuts down) |

## Example session

```
> java Main
Server started!
```

```
> java Main -t set -k text -v "Hello World!"
Client started!
Sent: {"type":"set","key":"text","value":"Hello World!"}
Received: {"response":"OK"}

> java Main -in setFile.json
Client started!
Sent: {"type":"set","key":"person","value":{"name":"Elon Musk","car":{"model":"Tesla Roadster","year":"2018"},"rocket":{"name":"Falcon 9","launches":"87"}}}
Received: {"response":"OK"}

> java Main -in getFile.json
Client started!
Sent: {"type":"get","key":["person","name"]}
Received: {"response":"OK","value":"Elon Musk"}

> java Main -in updateFile.json
Client started!
Sent: {"type":"set","key":["person","rocket","launches"],"value":"88"}
Received: {"response":"OK"}

> java Main -in deleteFile.json
Client started!
Sent: {"type":"delete","key":["person","car","year"]}
Received: {"response":"OK"}

> java Main -in secondGetFile.json
Client started!
Sent: {"type":"get","key":["person"]}
Received: {"response":"OK","value":{"name":"Elon Musk","car":{"model":"Tesla Roadster"},"rocket":{"name":"Falcon 9","launches":"88"}}}

> java Main -t exit
Client started!
Sent: {"type":"exit"}
Received: {"response":"OK"}
```

## Running the tests

```
./gradlew ":JSON_Database_with_Java-task:test"
```

## Stages

1. **Create a database**: an in-memory array of 1000 text cells with `set`, `get` and `delete`.
2. **Connect it to a server**: a basic socket exchange between client and server.
3. **Add new functionalities**: database commands over the socket, with arguments parsed by JCommander.
4. **Start work with JSON**: string keys, and JSON requests and responses with Gson.
5. **Manage multiple requests**: data saved to a file, an executor for parallel requests, a read/write lock, and `-in` request files.
6. **Store JSON objects in your database**: any JSON value and nested keys given as paths.
