# Maestro

Maestro is the easiest way to automate UI testing for your mobile app.

> [!NOTE]
>
> **Full documentation for Maestro can be found at [maestro.mobile.dev](https://maestro.mobile.dev)**
>
> Since this is forked REPO, to install this maestro, please use
>
> `curl -Ls "https://raw.githubusercontent.com/rasyid7/maestro/main/scripts/install.sh" | bash`

<img src="https://user-images.githubusercontent.com/847683/187275009-ddbdf963-ce1d-4e07-ac08-b10f145e8894.gif" />

## Why Maestro?

Maestro is built on learnings from its predecessors (Appium, Espresso, UIAutomator, XCTest)

- Built-in tolerance to flakiness. UI elements will not always be where you expect them, screen tap will not always go through, etc. Maestro embraces the instability of mobile applications and devices and tries to counter it.
- Built-in tolerance to delays. No need to pepper your tests with `sleep()` calls. Maestro knows that it might take time to load the content (i.e. over the network) and automatically waits for it (but no longer than required).
- Blazingly fast iteration. Tests are interpreted, no need to compile anything. Maestro is able to continuously monitor your test files and rerun them as they change.
- Declarative yet powerful syntax. Define your tests in a `yaml` file.
- Simple setup. Maestro is a single binary that works anywhere.

## Resources

### Documentation

**Available at [maestro.mobile.dev](https://maestro.mobile.dev)**

To get more background on why we built Maestro, read [the Maestro announcement blogpost][blogpost].

### Community

We invite everyone to [join our public Slack channel](https://docsend.com/view/3r2sf8fvvcjxvbtk).

### Contributing

Maestro is an open-source project and we love getting contributions.

To get started, take a look at [issues with the `good first issue` label][good first issues] and [the contributing guide](./CONTRIBUTING.md).

### Built by

[mobile.dev](https://www.mobile.dev)

![](./logo.png)

[good first issues]: https://github.com/mobile-dev-inc/maestro/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22
[blogpost]: https://blog.mobile.dev/introducing-maestro-painless-mobile-ui-automation-bee4992d13c1

