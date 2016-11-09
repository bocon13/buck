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

package com.facebook.buck.rules.macros;

import com.facebook.buck.jvm.java.DefaultJavaLibrary;
import com.facebook.buck.jvm.java.JavaTest;
import com.facebook.buck.model.MacroException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.SourcePathResolver;

import java.util.stream.Collectors;

/**
 * Resolves to the sources for a build target.
 */
public class SourcesMacroExpander extends BuildTargetMacroExpander {
  @Override
  public String expand(SourcePathResolver resolver, BuildRule rule)
      throws MacroException {
    if (rule instanceof JavaTest) {
      rule = ((JavaTest) rule).getCompiledTestsLibrary();
    }
    if (rule instanceof DefaultJavaLibrary) {
      DefaultJavaLibrary javaLibrary = (DefaultJavaLibrary) rule;
      return javaLibrary.getSources().stream()
          .map(src -> resolver.getAbsolutePath(src).toAbsolutePath().toString())
          .collect(Collectors.joining(" "));
    }
    throw new MacroException(String.format(
        "%s used in srcs macro is of unsupported type: %s",
        rule.getBuildTarget(),
        rule.getType()));
  }
}
