package com.julienviet.releaser;

import io.vertx.core.*;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.*;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:julien@julienviet.com">Julien Viet</a>
 */
@Mojo(name = "proxy", aggregator = true)
public class ProxyMojo extends AbstractMojo {

  @Parameter(property = "stagingProfileId")
  private String stagingProfileId;

  @Parameter(property = "stagingUsername")
  private String stagingUsername;

  @Parameter(property = "stagingPassword")
  private String stagingPassword;

  @Parameter(property = "proxyPort", defaultValue = "8080")
  private int proxyPort;

  private Vertx vertx;
  private ConcurrentMap<String, Buffer> map = new ConcurrentHashMap<>();
  private Staging staging;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    if (stagingUsername == null) {
      throw new MojoFailureException("Your must provide -DstagingUsername=XXX");
    }
    if (stagingPassword == null) {
      throw new MojoFailureException("Your must provide -DstagingPassword=XXX");
    }
    if (stagingProfileId == null) {
      throw new MojoFailureException("Your must provide -DstagingProfileId=XXX");
    }

    vertx = Vertx.vertx();

    HttpServer server = vertx.createHttpServer(new HttpServerOptions());

    server.requestHandler(req -> {

      HttpMethod method = req.method();
      if (req.path().equals("/repo") && method == HttpMethod.GET) {
        JsonObject result = new JsonObject();
        map.forEach((path, body) -> {
          result.put(path, body.length());
        });
        req.response().putHeader("Content-Type", "application/json").end(result.encode());
        return;
      } else if (req.path().startsWith("/repo/")) {
        String path = req.path().substring("/repo".length());
        if (method == HttpMethod.OPTIONS) {
          req.response().putHeader("Allow", "OPTIONS, GET, PUT").end();
          return;
        } else if (method == HttpMethod.PUT) {
          req.bodyHandler(body -> {
            System.out.println("Stored " + path);
            map.put(path, body);
            req.response().end();
          });
          return;
        } else if (method == HttpMethod.GET) {
          Buffer buffer = map.get(path);
          if (buffer == null) {
            req.response().setStatusCode(404).end();
          } else {
            req.response().end(buffer);
          }
          return;
        }
      } else if (req.path().equals("/stage")) {
        if (method == HttpMethod.GET) {
          JsonObject result = new JsonObject();
          if (staging != null) {
            long staged = staging.uploads.values().stream().mapToInt(upload -> upload.result.succeeded() ? 1 : 0).sum();
            long failed = staging.uploads.values().stream().mapToInt(upload -> upload.result.failed() ? 1 : 0).sum();
            long pending = staging.uploads.values().stream().mapToInt(upload -> upload.result.isComplete() ? 0 : 1).sum();
            JsonArray errors = new JsonArray(staging.uploads.values().stream().filter(upload -> upload.result.failed()).map(upload -> upload.result.cause().getMessage()).collect(Collectors.toList()));
            result.put("repositoryId", staging.repositoryId);
            result.put("staged", staged);
            result.put("failed", failed);
            result.put("pending", pending);
            result.put("errors", errors);
            result.put("staging", true);
          } else {
            result.put("staging", false);
          }
          req.response().putHeader("Content-Type", "application/json").end(result.encode());
          return;
        } else if (method == HttpMethod.POST) {
          if (staging != null) {
            // Conflict
            req.response().setStatusCode(409).end();
          } else {
            staging = new Staging(map);
            long start = System.currentTimeMillis();
            staging.stage(ar -> {
              staging = null;
              if (ar.succeeded()) {
                long l = System.currentTimeMillis() - start;
                final long hr = TimeUnit.MILLISECONDS.toHours(l);
                final long min = TimeUnit.MILLISECONDS.toMinutes(l - TimeUnit.HOURS.toMillis(hr));
                final long sec = TimeUnit.MILLISECONDS.toSeconds(l - TimeUnit.HOURS.toMillis(hr) - TimeUnit.MINUTES.toMillis(min));
                final long ms = TimeUnit.MILLISECONDS.toMillis(l - TimeUnit.HOURS.toMillis(hr) - TimeUnit.MINUTES.toMillis(min) - TimeUnit.SECONDS.toMillis(sec));
                String t =  String.format("%02d:%02d:%02d.%03d", hr, min, sec, ms);
                System.out.println("Repository staged in " + t);
              } else {
                ar.cause().printStackTrace();
              }
            });
            req.response().end();
          }
          return;
        }
      }
      System.out.println("Unhandled " + req.path() + " " + method);
    });

    CompletableFuture<Void> sync = new CompletableFuture<>();
    server.listen(proxyPort, ar -> {
      if (ar.succeeded()) {
        sync.complete(null);
      } else {
        sync.completeExceptionally(ar.cause());
      }
    });

    try {
      sync.get();
      System.out.println("Proxy started, you can deploy to http://localhost:" + proxyPort +
              "/repo and use http://localhost/stage");
      CountDownLatch latch = new CountDownLatch(1);
      latch.await();
    } catch (Exception ignore) {
    } finally {
      vertx.close();
    }
  }

  private class Staging {

    private final HttpClient client;
    private final Map<String, Upload> uploads = new HashMap<>();
    private String repositoryId;

    Staging(Map<String, Buffer> resources) {
      HttpClientOptions options = new HttpClientOptions();
      options.setDefaultPort(443);
      options.setDefaultHost("oss.sonatype.org");
      options.setSsl(true);
      options.setPipelining(true);
      options.setKeepAlive(true);
      options.setPipeliningLimit(10);
      options.setMaxPoolSize(5);
      options.setTrustAll(true);
      client = vertx.createHttpClient(options);
      for (Map.Entry<String, Buffer> resource : resources.entrySet()) {
        String path = resource.getKey();
        Buffer body = resource.getValue();
        uploads.put(path, new Upload(path, body));
      }
    }

    class Upload {
      final String path;
      final Buffer body;
      final Future<Void> result;
      Throwable last;
      Upload(String path, Buffer body) {
        this.path = path;
        this.body = body;
        this.result = Future.future();
      }

      void upload() {
        String requestUri = "/service/local/staging/deployByRepositoryId/" + repositoryId + path;
        upload(requestUri, body, 0);
      }

      private void upload(String requestUri, Buffer body, int retries) {
        Future<Void> fut = Future.future();
        fut.setHandler(ar -> {
          if (ar.succeeded()) {
            map.remove(path);
            result.tryComplete();
          } else {
            if (retries < 8) {
              upload(requestUri, body, retries + 1);
            } else {
              result.tryFail("Failed to upload " + requestUri + ": " + ar.cause().getMessage());
            }
          }
        });
        HttpClientRequest put = createRequest(HttpMethod.PUT, requestUri);
        put.handler(resp -> {
          resp.bodyHandler(e -> {
            if (resp.statusCode() == 201) {
              System.out.println("Uploaded " + requestUri);
              fut.tryComplete();
            } else {
              fut.tryFail(invalidResponse(HttpMethod.PUT, requestUri, resp.statusCode(), body));
            }
          });
        });
        put.exceptionHandler(fut::tryFail);
        put.end(body);
      }
    }

    void stage(Handler<AsyncResult<CompositeFuture>> handler) {
      createStagingRepo(ar -> {
        if (ar.succeeded()) {
          repositoryId = ar.result();
          List<Future> futures = new ArrayList<>();
          for (Upload upload : uploads.values()) {
            futures.add(upload.result);
            upload.upload();
          }
          CompositeFuture fut = CompositeFuture.join(futures);
          fut.setHandler(handler);
        } else {
          handler.handle(ar.mapEmpty());
        }
      });
    }

    void createStagingRepo(Handler<AsyncResult<String>> resultHandler) {
      Future<String> fut = Future.future();
      fut.setHandler(resultHandler);
      String requestUri = "/service/local/staging/profiles/" + stagingProfileId + "/start";
      HttpClientRequest post = createRequest(HttpMethod.POST, requestUri);
      post.putHeader("Content-Type", "application/xml");
      post.exceptionHandler(fut::tryFail);
      post.handler(resp -> {
        resp.bodyHandler(body -> {
          if (resp.statusCode() == 201) {
            String content = body.toString();
            int from = content.indexOf("<stagedRepositoryId>");
            int to = content.indexOf("</stagedRepositoryId>");
            if (from != -1 && to != -1) {
              String repoId = content.substring(from + "<stagedRepositoryId>".length(), to);
              fut.tryComplete(repoId);
              return;
            }
          }
          fut.tryFail(invalidResponse(HttpMethod.POST, requestUri, resp.statusCode(), body));
        });
      });
      post.end(Buffer.buffer("<promoteRequest>\n" +
              "  <data>\n" +
              "    <description>test description</description>\n" +
              "  </data>\n" +
              "</promoteRequest>\n"));

    }

    private String invalidResponse(HttpMethod method, String requestUri, int status, Buffer body) {
      StringBuilder msg = new StringBuilder("InvalidResponse[" + method + ", " + requestUri + ", " + status);
      if (body != null && body.length() > 0) {
        msg.append(", ");
        msg.append(body);
      }
      msg.append("]");
      return msg.toString();
    }

    HttpClientRequest createRequest(HttpMethod method, String uri) {
      HttpClientRequest request = client.request(method, uri);
      request.putHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((stagingUsername + ":" + stagingPassword).getBytes()));
      request.putHeader("Cache-control", "no-cache");
      request.putHeader("Cache-store", "no-store");
      request.putHeader("Pragma", "no-cache");
      request.putHeader("Expires", "0");
      request.putHeader("User-Agent", "Apache-Maven/3.5.0 (Java 1.8.0_112; Mac OS X 10.13)");
      return request;
    }
  }
}
