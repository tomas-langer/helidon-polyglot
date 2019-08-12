# Helidon Quickstart SE Example - polyglot

This project implements a simple Hello World REST service using Helidon SE and extends the basic example with
polyglot implementation - routing with JavaScript and Ruby.

## Prerequisites

1. Maven 3.5 or newer
2. Java SE 8 or newer
3. GraalVM 19.0 or newer

Verify prerequisites
```
java -version
mvn --version
docker --version
minikube version
kubectl version --short
```

## Create a polyglot service

1. Create a JavaScript file to use in Helidon, such as `helidon.js` in resources folder (this repository uses a class)
2. Add methods to this file to handle the default greeting (method `helloWorld`) and greeting with a name parameter (`hello`)
    1. Both methods have parameters `request` and `response` - same as any Helidon handler method
3. Add a dependency on `org.graalvm.sdk:graal-sdk:${graal.version}` in `provided` scope (we must run on GraalVM, so this is provied)

And now we can modify the `GreetService` to use functions from other languages for routing

### Ruby
Ruby has the advantage of being multithreaded, so we can use a Ruby function easily.
We prepare a context for Ruby (all done in a constructor of `GreetService`):
```java
Context rubyContext = Context.newBuilder()
            .allowHostAccess(HostAccess.ALL)
            .build();
```
and then we create the actual function to use (and assign it to a field for further use):
```java
private final Value rubyFunction;
//...
this.rubyFunction = rubyContext.eval("ruby", "-> (request, response) {\n"
                + "  response.send(\"Hello World from Ruby\");\n"
                + "}");
```

Needed imports:
```java
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
```

### JavaScript
JavaScript is a single threaded language and we must make sure that access to it is not parallel. In this example,
we achieve that by serializing access using a single threaded executor service.
We prepare a context and executor:
```java
Context jsContext = Context.newBuilder()
        .allowHostAccess(HostAccess.newBuilder(HostAccess.ALL)
        // example to allow access to parameters, yet disable access directly to WebServer, to prevent shutdown etc.
                                 .denyAccess(WebServer.class)
                                 .build())
        .build();

ExecutorService svc = Executors.newSingleThreadExecutor();
```
We need a different context than for our Ruby example, as the context is aware of languages it handles and would be single
threaded even for Ruby.

Next we load the javascript file and prepare the `Value` used as a method handle:
```java
Source jsSource = Source.newBuilder("js", GreetService.class.getResource("/jsClass.js")).buildLiteral();
Value jsHandler = jsContext.eval(jsSource);
``` 

Finally we create our two handlers for the default message and for the named one:
```java
private final BiConsumer<ServerRequest, ServerResponse> jsDefault;
private final BiConsumer<ServerRequest, ServerResponse> jsNamed;

//...
this.jsDefault = jsMethod(svc, jsHandler, "helloWorld");
this.jsNamed = jsMethod(svc, jsHandler, "hello");
```

Using the following `jsMethod` helper:
```java
private static BiConsumer<ServerRequest, ServerResponse> jsMethod(ExecutorService svc,
                                                                      Value jsHandler,
                                                                      String identificator) {
    return (request, response) -> {
        svc.submit(() -> {
            try {
                jsHandler.invokeMember(identificator, request, response);
            } catch (Exception e) {
                response.status(500);
                response.send("Failed to process request");
                e.printStackTrace();
            }
        });
    };
}
```

### Polyglot routing
To finish our example, we want our routing to directly use the created methods.
We will modify the `update(Routing.Rules)` method:
```java
@Override
public void update(Routing.Rules rules) {
    rules.get("/", jsDefault::accept)
            // Ruby we can execute directly, as it supports multithreaded
            .get("/ruby", rubyFunction::execute)
            // JavaScript is wrapped in a BiConsumer to use the single thread executor service
            .get("/{name}", jsNamed::accept)
            // and one method is left for java to process
            .put("/greeting", this::updateGreetingHandler);
}
```

We return strings from these methods for the sake of clarity of the code.

## Build

```
mvn package
```

## Start the application

```
${GRAAL_HOME}/bin/java -jar target/helidon-quickstart-se.jar
```

## Exercise the application

```shell script
curl -X GET http://localhost:8080/greet
'Hello World from JavaScript Class!'

curl -X GET http://localhost:8080/greet/Joe
'Hello Joe from JavaScript Class!'

curl -X GET http://localhost:8080/greet/ruby
'Hello World from Ruby'
```

## Try health and metrics

```
curl -s -X GET http://localhost:8080/health
{"outcome":"UP",...
. . .

# Prometheus Format
curl -s -X GET http://localhost:8080/metrics
# TYPE base:gc_g1_young_generation_count gauge
. . .

# JSON Format
curl -H 'Accept: application/json' -X GET http://localhost:8080/metrics
{"base":...
. . .

```

# Notice
The steps below are from the original Helidon Quickstart that uses Oracle JDK. If you want this example to run with 
docker, you would need to use the GraalVM virtual machine!

Tests are also disabled, as they would need to run on GraalVM

## Build the Docker Image

```
docker build -t helidon-quickstart-se .
```

## Start the application with Docker

```
docker run --rm -p 8080:8080 helidon-quickstart-se:latest
```

Exercise the application as described above

## Deploy the application to Kubernetes

```
kubectl cluster-info                # Verify which cluster
kubectl get pods                    # Verify connectivity to cluster
kubectl create -f app.yaml   # Deply application
kubectl get service helidon-quickstart-se  # Get service info
```

## Native image with GraalVM

GraalVM allows you to compile your programs ahead-of-time into a native
 executable. See https://www.graalvm.org/docs/reference-manual/aot-compilation/
 for more information.

You can build a native executable in 2 different ways:
* With a local installation of GraalVM
* Using Docker

### Local build

Download Graal VM at https://github.com/oracle/graal/releases, the version
 currently supported for Helidon is `19.0.0`.

```
# Setup the environment
export GRAALVM_HOME=/path
# build the native executable
mvn package -Pnative-image
```

You can also put the Graal VM `bin` directory in your PATH, or pass
 `-DgraalVMHome=/path` to the Maven command.

See https://github.com/oracle/helidon-build-tools/tree/master/helidon-maven-plugin
 for more information.

Start the application:

```
./target/helidon-quickstart-se
```

### Multi-stage Docker build

Build the "native" Docker Image

```
docker build -t helidon-quickstart-se-native -f Dockerfile.native .
```

Start the application:

```
docker run --rm -p 8080:8080 helidon-quickstart-se-native:latest
```