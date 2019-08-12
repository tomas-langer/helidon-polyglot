/*
 * Copyright (c) 2018, 2019 Oracle and/or its affiliates. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.helidon.examples.quickstart.se;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.json.Json;
import javax.json.JsonBuilderFactory;
import javax.json.JsonObject;

import io.helidon.common.http.Http;
import io.helidon.config.Config;
import io.helidon.metrics.RegistryFactory;
import io.helidon.webserver.Routing;
import io.helidon.webserver.ServerRequest;
import io.helidon.webserver.ServerResponse;
import io.helidon.webserver.Service;
import io.helidon.webserver.WebServer;

import org.eclipse.microprofile.metrics.MetricRegistry;
import org.eclipse.microprofile.metrics.Timer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * A simple service to greet you. Examples:
 *
 * Get default greeting message:
 * curl -X GET http://localhost:8080/greet
 *
 * Get greeting message for Joe:
 * curl -X GET http://localhost:8080/greet/Joe
 *
 * Change greeting
 * curl -X PUT -H "Content-Type: application/json" -d '{"greeting" : "Howdy"}' http://localhost:8080/greet/greeting
 *
 * The message is returned as a JSON object
 */

public class GreetService implements Service {

    /**
     * The config value for the key {@code greeting}.
     */
    private final AtomicReference<String> greeting = new AtomicReference<>();

    private static final JsonBuilderFactory JSON = Json.createBuilderFactory(Collections.emptyMap());
    private final Value rubyFunction;
    private final BiConsumer<ServerRequest, ServerResponse> jsDefault;
    private final BiConsumer<ServerRequest, ServerResponse> jsNamed;

    GreetService(Config config) {
        greeting.set(config.get("app.greeting").asString().orElse("Ciao"));

        // a different context, as js makes it single threaded
        Context rubyContext = Context.newBuilder()
                .allowHostAccess(HostAccess.ALL)
                .build();

        this.rubyFunction = rubyContext.eval("ruby", "-> (request, response) {\n"
                + "  response.send(\"Hello World from Ruby\");\n"
                + "}");

        Context jsContext = Context.newBuilder()
                .allowHostAccess(HostAccess.newBuilder(HostAccess.ALL)
                                         .denyAccess(WebServer.class)
                                         .build())
                .build();
        ExecutorService svc = Executors.newSingleThreadExecutor();


        // JS is single threaded, using a single thread executor, to make sure we do so
        // Helidon does not care, as response may be written from any thread in the future
        Source jsSource = Source.newBuilder("js", GreetService.class.getResource("/helidon.js")).buildLiteral();
        Value jsHandler = jsContext.eval(jsSource);

        this.jsDefault = jsMethod(svc, jsHandler, "helloWorld");
        this.jsNamed = jsMethod(svc, jsHandler, "hello");
    }

    /**
     * A service registers itself by updating the routine rules.
     * @param rules the routing rules.
     */
    @Override
    public void update(Routing.Rules rules) {
        rules.get("/", jsDefault::accept)
                .get("/ruby", rubyFunction::execute)
                .get("/{name}", jsNamed::accept)
                .put("/greeting", this::updateGreetingHandler);
    }

    private void sendResponse(ServerResponse response, String name) {
        String msg = String.format("%s %s!", greeting.get(), name);

        JsonObject returnObject = JSON.createObjectBuilder()
                .add("message", msg)
                .build();
        response.send(returnObject);
    }

    private void updateGreetingFromJson(JsonObject jo, ServerResponse response) {

        if (!jo.containsKey("greeting")) {
            JsonObject jsonErrorObject = JSON.createObjectBuilder()
                    .add("error", "No greeting provided")
                    .build();
            response.status(Http.Status.BAD_REQUEST_400)
                    .send(jsonErrorObject);
            return;
        }

        greeting.set(jo.getString("greeting"));
        response.status(Http.Status.NO_CONTENT_204).send();
    }

    /**
     * Set the greeting to use in future messages.
     * @param request the server request
     * @param response the server response
     */
    private void updateGreetingHandler(ServerRequest request,
                                       ServerResponse response) {
        request.content().as(JsonObject.class).thenAccept(jo -> updateGreetingFromJson(jo, response));
    }

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
}
