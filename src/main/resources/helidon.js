class Helidon {
  helloWorld(request, response) {
    response.send("Hello World from JavaScript Class!");
  }
  hello(request, response) {
    response.send("Hello " + request.path().param("name") + " from JavaScript Class!");
  }
}
new Helidon();