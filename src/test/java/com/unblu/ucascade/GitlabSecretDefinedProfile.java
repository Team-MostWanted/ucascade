package com.unblu.ucascade;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

public class GitlabSecretDefinedProfile implements QuarkusTestProfile {

	private static final String SECRET = "_a:secret-for!tests_";

	@Override
	public Map<String, String> getConfigOverrides() {
		return Map.of("gitlab.webhook.secret", SECRET);
	}
}
