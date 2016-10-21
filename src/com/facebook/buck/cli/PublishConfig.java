/*
 * Copyright 2016-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.cli;

import com.google.common.base.Function;
import com.google.common.base.Optional;

import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;

import javax.annotation.Nullable;

public class PublishConfig {
  private static final String PUBLISH_SECION = "publish";

  private final BuckConfig delegate;

  public PublishConfig(BuckConfig delegate) {
    this.delegate = delegate;
  }

  public Optional<URL> getPublishRepoUrl() {
    return delegate.getValue(PUBLISH_SECION, "maven_url").transform(new Function<String, URL>() {
      @Nullable
      @Override
      public URL apply(@Nullable String input) {
        try {
          return input != null ? new URL(input) : null;
        } catch (MalformedURLException e) {
          return null;
        }
      }
    });
  }

  public Optional<PasswordAuthentication> getRepoCredentials() {
    Optional<String> user = delegate.getValue(PUBLISH_SECION, "maven_user");
    Optional<String> password = delegate.getValue(PUBLISH_SECION, "maven_password");
    if (!user.isPresent() || !password.isPresent()) {
      return Optional.absent();
    }

    return Optional.of(new PasswordAuthentication(user.get(), password.get().toCharArray()));
  }

  public Optional<String> getPpgKeyring() {
    return delegate.getValue(PUBLISH_SECION, "pgp_keyring");
  }

  public char[] getPgpPassword() {
    return delegate.getValue(PUBLISH_SECION, "pgp_pasword").or("").toCharArray();
  }

  public Optional<String> getLocalRepo() {
    return delegate.getValue(PUBLISH_SECION, "local_repo")
        .or(Optional.fromNullable(System.getenv("M2_REPO")));
  }

  /* FIXME BOC consider falling back to download config
  public Optional<String> getMavenRepo() {
    return delegate.getValue("download", "maven_repo");
  }

  public ImmutableMap<String, String> getAllMavenRepos() {
    ImmutableSortedMap.Builder<String, String> repos = ImmutableSortedMap.naturalOrder();
    repos.putAll(delegate.getEntriesForSection("maven_repositories"));

    Optional<String> defaultRepo = getMavenRepo();
    if (defaultRepo.isPresent()) {
      repos.put(defaultRepo.get(), defaultRepo.get());
    }

    return repos.build();
  }
  */
}
