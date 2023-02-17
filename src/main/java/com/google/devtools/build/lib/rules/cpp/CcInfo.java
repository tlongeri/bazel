// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.devtools.build.lib.rules.cpp;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.collect.nestedset.Depset;
import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable;
import com.google.devtools.build.lib.packages.BuiltinProvider;
import com.google.devtools.build.lib.packages.NativeInfo;
import com.google.devtools.build.lib.starlarkbuildapi.cpp.CcInfoApi;
import java.util.Collection;
import javax.annotation.Nullable;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** Provider for C++ compilation and linking information. */
@Immutable
public final class CcInfo extends NativeInfo implements CcInfoApi<Artifact> {
  public static final Provider PROVIDER = new Provider();
  public static final CcInfo EMPTY = builder().build();

  private final CcCompilationContext ccCompilationContext;
  private final CcLinkingContext ccLinkingContext;
  private final CcDebugInfoContext ccDebugInfoContext;
  private final CcNativeLibraryInfo ccNativeLibraryInfo;

  public CcInfo(
      CcCompilationContext ccCompilationContext,
      CcLinkingContext ccLinkingContext,
      CcDebugInfoContext ccDebugInfoContext,
      CcNativeLibraryInfo ccNativeLibraryInfo) {
    this.ccCompilationContext = ccCompilationContext;
    this.ccLinkingContext = ccLinkingContext;
    this.ccDebugInfoContext = ccDebugInfoContext;
    this.ccNativeLibraryInfo = ccNativeLibraryInfo;
  }

  @Override
  public Provider getProvider() {
    return PROVIDER;
  }

  @Override
  public CcCompilationContext getCcCompilationContext() {
    return ccCompilationContext;
  }

  @Override
  public CcLinkingContext getCcLinkingContext() {
    return ccLinkingContext;
  }

  @Override
  public CcDebugInfoContext getCcDebugInfoContextFromStarlark(StarlarkThread thread)
      throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return getCcDebugInfoContext();
  }

  @Override
  public Depset getCcTransitiveNativeLibraries(StarlarkThread thread) throws EvalException {
    CcModule.checkPrivateStarlarkificationAllowlist(thread);
    return Depset.of(LibraryToLink.TYPE, getCcNativeLibraryInfo().getTransitiveCcNativeLibraries());
  }

  public CcDebugInfoContext getCcDebugInfoContext() {
    return ccDebugInfoContext;
  }

  public CcNativeLibraryInfo getCcNativeLibraryInfo() {
    return ccNativeLibraryInfo;
  }

  public static CcInfo merge(Collection<CcInfo> ccInfos) {
    return merge(ImmutableList.of(), ccInfos);
  }

  public static CcInfo merge(Collection<CcInfo> directCcInfos, Collection<CcInfo> ccInfos) {
    ImmutableList.Builder<CcCompilationContext> directCcCompilationContexts =
        ImmutableList.builder();
    ImmutableList.Builder<CcCompilationContext> ccCompilationContexts = ImmutableList.builder();
    ImmutableList.Builder<CcLinkingContext> ccLinkingContexts = ImmutableList.builder();
    ImmutableList.Builder<CcDebugInfoContext> ccDebugInfoContexts = ImmutableList.builder();
    ImmutableList.Builder<CcNativeLibraryInfo> ccNativeLibraryInfos = ImmutableList.builder();

    for (CcInfo ccInfo : directCcInfos) {
      directCcCompilationContexts.add(ccInfo.getCcCompilationContext());
      ccLinkingContexts.add(ccInfo.getCcLinkingContext());
      ccDebugInfoContexts.add(ccInfo.getCcDebugInfoContext());
      ccNativeLibraryInfos.add(ccInfo.getCcNativeLibraryInfo());
    }
    for (CcInfo ccInfo : ccInfos) {
      ccCompilationContexts.add(ccInfo.getCcCompilationContext());
      ccLinkingContexts.add(ccInfo.getCcLinkingContext());
      ccDebugInfoContexts.add(ccInfo.getCcDebugInfoContext());
      ccNativeLibraryInfos.add(ccInfo.getCcNativeLibraryInfo());
    }

    CcCompilationContext.Builder builder =
        CcCompilationContext.builder(
            /* actionConstructionContext= */ null, /* configuration= */ null, /* label= */ null);

    return new CcInfo(
        builder
            .addDependentCcCompilationContexts(
                directCcCompilationContexts.build(), ccCompilationContexts.build())
            .build(),
        CcLinkingContext.merge(ccLinkingContexts.build()),
        CcDebugInfoContext.merge(ccDebugInfoContexts.build()),
        CcNativeLibraryInfo.merge(ccNativeLibraryInfos.build()));
  }

  @Override
  public boolean equals(Object otherObject) {
    if (!(otherObject instanceof CcInfo)) {
      return false;
    }
    CcInfo other = (CcInfo) otherObject;
    if (this == other) {
      return true;
    }
    if (!this.ccCompilationContext.equals(other.ccCompilationContext)
        || !this.ccDebugInfoContext.equals(other.ccDebugInfoContext)
        || !this.getCcLinkingContext().equals(other.getCcLinkingContext())
        || !this.getCcNativeLibraryInfo().equals(other.getCcNativeLibraryInfo())) {
      return false;
    }
    return true;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(ccCompilationContext, ccLinkingContext, ccDebugInfoContext);
  }

  public static Builder builder() {
    // private to avoid class initialization deadlock between this class and its outer class
    return new Builder();
  }

  /** A Builder for {@link CcInfo}. */
  public static class Builder {
    private CcCompilationContext ccCompilationContext;
    private CcLinkingContext ccLinkingContext;
    private CcDebugInfoContext ccDebugInfoContext;
    private CcNativeLibraryInfo ccNativeLibraryInfo;

    private Builder() {}

    public CcInfo.Builder setCcCompilationContext(CcCompilationContext ccCompilationContext) {
      Preconditions.checkState(this.ccCompilationContext == null);
      this.ccCompilationContext = ccCompilationContext;
      return this;
    }

    public CcInfo.Builder setCcLinkingContext(CcLinkingContext ccLinkingContext) {
      Preconditions.checkState(this.ccLinkingContext == null);
      this.ccLinkingContext = ccLinkingContext;
      return this;
    }

    public CcInfo.Builder setCcDebugInfoContext(CcDebugInfoContext ccDebugInfoContext) {
      Preconditions.checkState(this.ccDebugInfoContext == null);
      this.ccDebugInfoContext = ccDebugInfoContext;
      return this;
    }

    public CcInfo.Builder setCcNativeLibraryInfo(CcNativeLibraryInfo ccNativeLibraryInfo) {
      Preconditions.checkState(this.ccNativeLibraryInfo == null);
      this.ccNativeLibraryInfo = ccNativeLibraryInfo;
      return this;
    }

    public CcInfo build() {
      if (ccCompilationContext == null) {
        ccCompilationContext = CcCompilationContext.EMPTY;
      }
      if (ccLinkingContext == null) {
        ccLinkingContext = CcLinkingContext.EMPTY;
      }
      if (ccDebugInfoContext == null) {
        ccDebugInfoContext = CcDebugInfoContext.EMPTY;
      }
      if (ccNativeLibraryInfo == null) {
        ccNativeLibraryInfo = CcNativeLibraryInfo.EMPTY;
      }
      return new CcInfo(
          ccCompilationContext, ccLinkingContext, ccDebugInfoContext, ccNativeLibraryInfo);
    }
  }

  /** Provider class for {@link CcInfo} objects. */
  public static class Provider extends BuiltinProvider<CcInfo>
      implements CcInfoApi.Provider<Artifact> {
    private Provider() {
      super(CcInfoApi.NAME, CcInfo.class);
    }

    @Override
    public CcInfoApi<Artifact> createInfo(
        Object starlarkCcCompilationContext,
        Object starlarkCcLinkingInfo,
        Object starlarkCcDebugInfo,
        Object starlarkCcNativeLibraryInfo,
        StarlarkThread thread)
        throws EvalException {
      CcCompilationContext ccCompilationContext =
          nullIfNone(starlarkCcCompilationContext, CcCompilationContext.class);
      CcLinkingContext ccLinkingContext = nullIfNone(starlarkCcLinkingInfo, CcLinkingContext.class);
      CcDebugInfoContext ccDebugInfoContext =
          nullIfNone(starlarkCcDebugInfo, CcDebugInfoContext.class);
      CcNativeLibraryInfo ccNativeLibraryInfo =
          nullIfNone(starlarkCcNativeLibraryInfo, CcNativeLibraryInfo.class);
      CcInfo.Builder ccInfoBuilder = CcInfo.builder();
      if (ccCompilationContext != null) {
        ccInfoBuilder.setCcCompilationContext(ccCompilationContext);
      }
      if (ccLinkingContext != null) {
        ccInfoBuilder.setCcLinkingContext(ccLinkingContext);
      }
      if (ccDebugInfoContext != null) {
        ccInfoBuilder.setCcDebugInfoContext(ccDebugInfoContext);
      }
      if (ccNativeLibraryInfo != null) {
        CcModule.checkPrivateStarlarkificationAllowlist(thread);
        ccInfoBuilder.setCcNativeLibraryInfo(ccNativeLibraryInfo);
      }
      return ccInfoBuilder.build();
    }

    @Nullable
    private static <T> T nullIfNone(Object object, Class<T> type) {
      return object != Starlark.NONE ? type.cast(object) : null;
    }
  }
}
