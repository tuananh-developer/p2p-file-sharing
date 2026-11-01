import { createInterface } from "node:readline";

function write(message) {
  process.stdout.write(JSON.stringify(message) + "\n");
}

write({ type: "event", event: "backendStatusChanged", data: { status: "READY" } });

const input = createInterface({ input: process.stdin });
input.on("line", (line) => {
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    process.exit(10);
    return;
  }

  if (request.command === "test.hang") {
    return;
  }

  if (request.command === "test.exit") {
    process.exit(2);
    return;
  }

  if (request.command === "test.invalid") {
    process.stdout.write("not-json\n");
    return;
  }

  if (request.command === "app.shutdown") {
    write({
      type: "response",
      id: request.id,
      success: true,
      data: { status: "SHUTDOWN" }
    });
    write({ type: "event", event: "backendStatusChanged", data: { status: "STOPPED" } });
    setTimeout(() => process.exit(0), 20);
    return;
  }

  write({
    type: "response",
    id: request.id,
    success: true,
    data: { echo: request.payload }
  });
  write({
    type: "event",
    event: "activityLogged",
    data: { message: "fake bridge handled " + request.command, level: "INFO" }
  });
});
