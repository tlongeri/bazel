# Copyright 2015 The Bazel Authors. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Rules for manipulation of various packaging."""

load(":path.bzl", "compute_data_path", "dest_path")

# Filetype to restrict inputs
tar_filetype = [".tar", ".tar.gz", ".tgz", ".tar.bz2"]

def _remap(remap_paths, path):
    """If path starts with a key in remap_paths, rewrite it."""
    for prefix, replacement in remap_paths.items():
        if path.startswith(prefix):
            return replacement + path[len(prefix):]
    return path

def _quote(filename, protect = "="):
    """Quote the filename, by escaping = by \\= and \\ by \\\\"""
    return filename.replace("\\", "\\\\").replace(protect, "\\" + protect)

def _pkg_tar_impl(ctx):
    """Implementation of the pkg_tar rule."""

    # Compute the relative path
    data_path = compute_data_path(ctx.outputs.out, ctx.attr.strip_prefix)

    # Find a list of path remappings to apply.
    remap_paths = ctx.attr.remap_paths

    # Start building the arguments.
    args = [
        "--output=" + ctx.outputs.out.path,
        "--directory=" + ctx.attr.package_dir,
        "--mode=" + ctx.attr.mode,
        "--owner=" + ctx.attr.owner,
        "--owner_name=" + ctx.attr.ownername,
    ]

    # Add runfiles if requested
    file_inputs = []
    if ctx.attr.include_runfiles:
        runfiles_depsets = []
        for f in ctx.attr.srcs:
            default_runfiles = f[DefaultInfo].default_runfiles
            if default_runfiles != None:
                runfiles_depsets.append(default_runfiles.files)

        # deduplicates files in srcs attribute and their runfiles
        file_inputs = depset(ctx.files.srcs, transitive = runfiles_depsets).to_list()
    else:
        file_inputs = ctx.files.srcs[:]

    args += [
        "--file=%s=%s" % (_quote(f.path), _remap(remap_paths, dest_path(f, data_path)))
        for f in file_inputs
    ]
    for target, f_dest_path in ctx.attr.files.items():
        target_files = target.files.to_list()
        if len(target_files) != 1:
            fail("Each input must describe exactly one file.", attr = "files")
        file_inputs += target_files
        args += ["--file=%s=%s" % (_quote(target_files[0].path), f_dest_path)]
    if ctx.attr.extension:
        dotPos = ctx.attr.extension.find(".")
        if dotPos > 0:
            dotPos += 1
            args += ["--compression=%s" % ctx.attr.extension[dotPos:]]
        elif ctx.attr.extension == "tgz":
            args += ["--compression=gz"]
    args += ["--tar=" + f.path for f in ctx.files.deps]
    arg_file = ctx.actions.declare_file(ctx.label.name + ".args")
    ctx.actions.write(arg_file, "\n".join(args))

    ctx.actions.run(
        inputs = file_inputs + ctx.files.deps + [arg_file],
        executable = ctx.executable.build_tar,
        arguments = ["--flagfile", arg_file.path],
        outputs = [ctx.outputs.out],
        mnemonic = "PackageTar",
        use_default_shell_env = True,
    )

# A rule for creating a tar file, see README.md
_real_pkg_tar = rule(
    implementation = _pkg_tar_impl,
    attrs = {
        "strip_prefix": attr.string(),
        "package_dir": attr.string(default = "/"),
        "deps": attr.label_list(allow_files = tar_filetype),
        "srcs": attr.label_list(allow_files = True),
        "files": attr.label_keyed_string_dict(allow_files = True),
        "mode": attr.string(default = "0555"),
        "out": attr.output(),
        "owner": attr.string(default = "0.0"),
        "ownername": attr.string(default = "."),
        "extension": attr.string(default = "tar"),
        "include_runfiles": attr.bool(),
        "remap_paths": attr.string_dict(),
        # Implicit dependencies.
        "build_tar": attr.label(
            default = Label("//tools/build_defs/pkg:build_tar"),
            cfg = "host",
            executable = True,
            allow_files = True,
        ),
    },
)

def pkg_tar(name, **kwargs):
    extension = kwargs.get("extension") or "tar"
    _real_pkg_tar(
        name = name,
        out = name + "." + extension,
        **kwargs
    )
