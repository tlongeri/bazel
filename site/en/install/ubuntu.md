Project: /_project.yaml
Book: /_book.yaml

# Installing Bazel on Ubuntu

This page describes the options for installing Bazel on Ubuntu.
It also provides links to the Bazel completion scripts and the binary installer,
if needed as a backup option (for example, if you don't have admin access).

Supported Ubuntu Linux platforms:

*   18.04 (LTS)
*   16.04 (LTS)

Bazel should be compatible with other Ubuntu releases and Debian
"stretch" and above, but is untested and not guaranteed to work.

Install Bazel on Ubuntu using one of the following methods:

*   *Recommended*: [Use Bazelisk](/install/bazelisk)
*   [Use our custom APT repository](#install-on-ubuntu)
*   [Use the binary installer](#binary-installer)
*   [Compile Bazel from source](/install/compile-source)

**Note:** For Arm-based systems, the APT repository does not contain an `arm64`
release, and there is no binary installer available. Either use Bazelisk or
compile from source.

Bazel comes with two completion scripts. After installing Bazel, you can:

*   Access the [bash completion script](/install/completion#bash)
*   Install the [zsh completion script](/install/completion#zsh)

### Step 1: Add Bazel distribution URI as a package source {:#add-dis-uri}

## Using Bazel's apt repository {:#install-on-ubuntu}

**Note:** This is a one-time setup step.

```posix-terminal
sudo apt install apt-transport-https curl gnupg
curl -fsSL https://bazel.build/bazel-release.pub.gpg | gpg --dearmor > bazel.gpg
sudo mv bazel.gpg /etc/apt/trusted.gpg.d/
echo "deb [arch=amd64] https://storage.googleapis.com/bazel-apt stable jdk1.8" | sudo tee /etc/apt/sources.list.d/bazel.list
```

The component name "jdk1.8" is kept only for legacy reasons and doesn't relate
to supported or included JDK versions. Bazel releases are Java-version agnostic.
Changing the "jdk1.8" component name would break existing users of the repo.

### Step 2: Install and update Bazel {:#install-bazel}

```posix-terminal
sudo apt update && sudo apt install bazel
```

Once installed, you can upgrade to a newer version of Bazel as part of your normal system updates:

```posix-terminal
sudo apt update && sudo apt full-upgrade
```

The `bazel` package always installs the latest stable version of Bazel. You
can install specific, older versions of Bazel in addition to the latest one,
such as this:

```posix-terminal
sudo apt install bazel-1.0.0
```

This installs Bazel 1.0.0 as `/usr/bin/bazel-1.0.0` on your system. This
can be useful if you need a specific version of Bazel to build a project, for
example because it uses a `.bazelversion` file to explicitly state with which
Bazel version it should be built.

Optionally, you can set `bazel` to a specific version by creating a symlink:

```posix-terminal
sudo ln -s /usr/bin/bazel-1.0.0 /usr/bin/bazel
bazel --version  # 1.0.0
```

### Step 3: Install a JDK (optional) {:#install-jdk}

Bazel includes a private, bundled JRE as its runtime and doesn't require you to
install any specific version of Java.

However, if you want to build Java code using Bazel, you have to install a JDK.

```posix-terminal
# Ubuntu 16.04 (LTS) uses OpenJDK 8 by default:
sudo apt install openjdk-8-jdk

# Ubuntu 18.04 (LTS) uses OpenJDK 11 by default:
sudo apt install openjdk-11-jdk
```

## Using the binary installer {:#binary-installer}

Generally, you should use the apt repository, but the binary installer
can be useful if you don't have admin permissions on your machine or
can't add custom repositories.

The binary installers can be downloaded from Bazel's [GitHub releases page](https://github.com/bazelbuild/bazel/releases){: .external}.

The installer contains the Bazel binary and extracts it into your `$HOME/bin`
folder. Some additional libraries must be installed manually for Bazel to work.

### Step 1: Install required packages {:#install-packages}

Bazel needs a C++ compiler and unzip / zip in order to work:

```posix-terminal
sudo apt install g++ unzip zip
```

If you want to build Java code using Bazel, install a JDK:

```posix-terminal
# Ubuntu 16.04 (LTS) uses OpenJDK 8 by default:
sudo apt-get install openjdk-8-jdk

# Ubuntu 18.04 (LTS) uses OpenJDK 11 by default:
sudo apt-get install openjdk-11-jdk
```

### Step 2: Run the installer {:#run-installer}

Next, download the Bazel binary installer named `bazel-{{ '<var>' }}version{{ '</var>' }}-installer-linux-x86_64.sh`
from the [Bazel releases page on GitHub](https://github.com/bazelbuild/bazel/releases){: .external}.

Run it as follows:

```posix-terminal
chmod +x bazel-{{ '<var>' }}version{{ '</var>' }}-installer-linux-x86_64.sh

./bazel-{{ '<var>' }}version{{ '</var>' }}-installer-linux-x86_64.sh --user
```

The `--user` flag installs Bazel to the `$HOME/bin` directory on your system and
sets the `.bazelrc` path to `$HOME/.bazelrc`. Use the `--help` command to see
additional installation options.

### Step 3: Set up your environment {:#set-environment}

If you ran the Bazel installer with the `--user` flag as above, the Bazel
executable is installed in your `$HOME/bin` directory.
It's a good idea to add this directory to your default paths, as follows:

```posix-terminal
export PATH="$PATH:$HOME/bin"
```

You can also add this command to your `~/.bashrc` or `~/.zshrc` file to make it
permanent.
