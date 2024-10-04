package controller;

import java.util.Random;

import javax.inject.Inject;

import org.gitlab4j.api.webhook.MergeRequestEvent;

import controller.model.CascadeResult;
import controller.model.MergeRequestSimple;
import io.quarkus.logging.Log;
import io.quarkus.vertx.web.Body;
import io.quarkus.vertx.web.Header;
import io.quarkus.vertx.web.Route;
import io.quarkus.vertx.web.Route.HandlerType;
import io.quarkus.vertx.web.Route.HttpMethod;
import io.quarkus.vertx.web.RouteBase;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.ext.web.RoutingContext;
import io.vertx.mutiny.core.eventbus.EventBus;
import service.GitLabService;
import util.JsonUtils;

@RouteBase(produces = "application/json")
public class MergeRequestController {

    @ConfigProperty(name = "gitlab.webhook.secret")
	Optional<String> gitlabWebhookSecret;

	@Inject
	GitLabService gitLabService;

	@Inject
	EventBus eventsBus;

	@Route(path = "/ucascade/merge-request", order = 1, methods = HttpMethod.POST, type = HandlerType.NORMAL)
	public CascadeResult mergeRequest(@Header("X-Gitlab-Event-UUID") String gitlabEventUUID, @Header("X-Gitlab-Token") String gitlabToken, @Body MergeRequestEvent mrEvent, HttpServerResponse response) {
		Log.infof("GitlabEvent: '%s' | Received", gitlabEventUUID);
        if (isGitlabWebhookSecretValid(gitlabToken, gitlabEventUUID)) {
			MergeRequestSimple simpleEvent = JsonUtils.toSimpleEvent(mrEvent, gitlabEventUUID);
            // consumed by GitLabService class
            eventsBus.send(GitLabService.MERGE_REQUEST_EVENT, simpleEvent);
		}
		response.setStatusCode(202);
		return gitLabService.createResult(gitlabEventUUID);
	}

	@Route(path = "/ucascade/merge-request-blocking", order = 1, methods = HttpMethod.POST, type = HandlerType.BLOCKING)
	public CascadeResult mergeRequestBlocking(@Header("X-Gitlab-Event-UUID") String gitlabEventUUID, @Header("X-Gitlab-Token") String gitlabToken, @Body MergeRequestEvent mrEvent) {
		Log.infof("GitlabEvent: '%s' | Received (blocking)", gitlabEventUUID);
        if (isGitlabWebhookSecretValid(gitlabToken, gitlabEventUUID)) {
			MergeRequestSimple simpleEvent = JsonUtils.toSimpleEvent(mrEvent, gitlabEventUUID);
		    return gitLabService.mergeRequest(simpleEvent);
		}
        CascadeResult result = service.createResult(gitlabEventUUID);
		result.setError("Event skipped");
		return result;
	}

	@Route(path = "/ucascade/replay", order = 1, methods = HttpMethod.POST, type = HandlerType.BLOCKING)
	public CascadeResult replay(@Header("X-Gitlab-Token") String gitlabToken, @Body MergeRequestSimple mrSimple) {
		String gitlabEventUUID = mrSimple.getGitlabEventUUID();
		if (gitlabEventUUID == null) {
			gitlabEventUUID = "replay-" + new Random().nextInt(1000, 10000);
		}
		Log.infof("GitlabEvent: '%s' | Replay", gitlabEventUUID);
        if (isGitlabWebhookSecretValid(gitlabToken, gitlabEventUUID)) {
		    return gitLabService.mergeRequest(mrSimple);
        }
        CascadeResult result = service.createResult(gitlabEventUUID);
		result.setError("Event skipped");
		return result;
	}

    private boolean isGitlabWebhookSecretValid(String gitlabToken, String gitlabEventUUID) {
		if (gitlabToken != null) {
			if (gitlabWebhookSecret.isEmpty()) {
				Log.errorf("GitlabEvent: '%s' | Got an token value, but no secret is configured with the 'gitlab.webhook.secret' configuration", gitlabEventUUID);
				return false;
			} else {
				String expected = gitlabWebhookSecret.get()
				if (Objects.equals(expected, gitlabToken)) {
					Log.debugf("GitlabEvent: '%s' | Token value is correct", gitlabEventUUID);
					return true;
				} else {
					Log.infof("GitlabEvent: '%s' | Token value from Gitlab '%s' does not match with the expected one '%s'. Check the 'gitlab.webhook.secret' configuration", gitlabEventUUID, gitlabToken, expected);
					return false;
				}
			}
		} else {
			if (gitlabWebhookSecret.isEmpty()) {
				Log.debugf("GitlabEvent: '%s' | No token value configured, no secret configured", gitlabEventUUID);
				return true;
			} else {
				Log.warnf("GitlabEvent: '%s' | No token value send (is a secret configured in Gitlab?), but a secret is configuredwith the 'gitlab.webhook.secret' configuration", gitlabEventUUID);
				return false;
			}
		}
	}

	@Route(path = "/*", order = 2)
	public void other(@Header("X-Gitlab-Event-UUID") String gitlabEventUUID, RoutingContext rc) {
		String path = rc.request().path();
		if (path.startsWith("/q/health")) {
			// the module 'quarkus-smallrye-health' will answer:
			rc.next();
		} else {
			Log.infof("GitlabEvent: '%s' | Invalid path '%s' ", gitlabEventUUID, path);

			CascadeResult result = gitLabService.createResult(gitlabEventUUID);
			result.setError("Invalid path: " + path);
			String body = Json.encode(result);
			rc.response()
					.setStatusCode(202)
					.end(body);
		}
	}

	@Route(path = "/*", order = 3, type = HandlerType.FAILURE)
	public CascadeResult error(@Header("X-Gitlab-Event-UUID") String gitlabEventUUID, RoutingContext rc) {
		Throwable t = rc.failure();
		Log.warnf(t, "GitlabEvent: '%s' | Failed to handle request on path '%s' ", gitlabEventUUID, rc.request().path());

		CascadeResult result = gitLabService.createResult(gitlabEventUUID);
		if (t != null) {
			result.setError(t.getMessage());
		} else {
			result.setError("Unknown error");
		}
		rc.response().setStatusCode(202);
		return result;
	}
}
