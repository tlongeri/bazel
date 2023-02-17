---
layout: documentation
title: Creating a macro
category: extending
---

<div style="background-color: #EFCBCB; color: #AE2B2B;  border: 1px solid #AE2B2B; border-radius: 5px; border-left: 10px solid #AE2B2B; padding: 0.5em;">
<b>IMPORTANT:</b> The Bazel docs have moved! Please update your bookmark to <a href="https://bazel.build/rules/tutorial-creating-a-macro" style="color: #0000EE;">https://bazel.build/rules/tutorial-creating-a-macro</a>
<p/>
You can <a href="https://blog.bazel.build/2022/02/17/Launching-new-Bazel-site.html" style="color: #0000EE;">read about</a> the migration, and let us <a href="https://forms.gle/onkAkr2ZwBmcbWXj7" style="color: #0000EE;">know what you think</a>.
</div>


# Creating a Macro

Imagine that you need to run a tool as part of your build. For example, you
may want to generate or preprocess a source file, or compress a binary. In this
tutorial, you are going to create a macro that resizes an image.

Macros are suitable for simple tasks. If you want to do anything more
complicated, for example add support for a new programming language, consider
creating a [rule](rules.md). Rules give you more control and flexibility.

The easiest way to create a macro that resizes an image is to use a `genrule`:

``` python
genrule(
    name = "logo_miniature",
    srcs = ["logo.png"],
    outs = ["small_logo.png"],
    cmd = "convert $< -resize 100x100 $@",
)

cc_binary(
    name = "my_app",
    srcs = ["my_app.cc"],
    data = [":logo_miniature"],
)
```

If you need to resize more images, you may want to reuse the code. To do that,
define a function in a separate `.bzl` file, and call the file `miniature.bzl`:

``` python
def miniature(name, src, size="100x100", **kwargs):
  """Create a miniature of the src image.

  The generated file is prefixed with 'small_'.
  """
  native.genrule(
    name = name,
    srcs = [src],
    outs = ["small_" + src],
    cmd = "convert $< -resize " + size + " $@",
    **kwargs
  )
```

A few remarks:

* By convention, macros have a `name` argument, just like rules.

* To document the behavior of a macro, use
  [docstring](https://www.python.org/dev/peps/pep-0257/) like in Python.

* To call a `genrule`, or any other native rule, use `native.`.

* Use `**kwargs` to forward the extra arguments to the underlying `genrule`
  (it works just like in [Python](https://docs.python.org/3/tutorial/controlflow.html#keyword-arguments)).
  This is useful, so that a user can use standard attributes like `visibility`,
  or `tags`.

Now, use the macro from the `BUILD` file:

``` python
load("//path/to:miniature.bzl", "miniature")

miniature(
    name = "logo_miniature",
    src = "image.png",
)

cc_binary(
    name = "my_app",
    srcs = ["my_app.cc"],
    data = [":logo_miniature"],
)
```
