/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.rules;


import com.facebook.buck.android.AndroidDirectoryResolver;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.plugin.PluginManager;
import com.facebook.buck.util.ProcessExecutor;
import com.google.common.base.Optional;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Contain items used to construct a {@link KnownBuildRuleTypes} that are shared between all
 * {@link Cell} instances.
 */
public class KnownBuildRuleTypesFactory {

  private final ProcessExecutor executor;
  private final AndroidDirectoryResolver directoryResolver;
  private final Optional<Path> testTempDirOverride;
  private final PluginManager pluginManager;

  public KnownBuildRuleTypesFactory(
      ProcessExecutor executor,
      AndroidDirectoryResolver directoryResolver,
      Optional<Path> testTempDirOverride,
      PluginManager pluginManager) {
    this.executor = executor;
    this.directoryResolver = directoryResolver;
    this.testTempDirOverride = testTempDirOverride;
    this.pluginManager = pluginManager;
  }

  public KnownBuildRuleTypes create(BuckConfig config) throws IOException, InterruptedException {
    return KnownBuildRuleTypes.createInstance(
        config,
        executor,
        directoryResolver,
        testTempDirOverride,
        pluginManager);
  }

}
