# 酒狐插件

一个联动插件，为《车万女仆》(Touhou Little Maid) 模组与《N.E.K.O》建立兼容与交互桥梁，可以用N.E.K.O用自然语言控制游戏内女仆行为

## Development

This repository is meant to live at:

```text
N.E.K.O/plugin/plugins/neko_tlm
```

When publishing to the plugin market, use this GitHub repository name:

```text
n.e.k.o_plugin_neko_tlm
```

From this plugin repository root:

```bash
uvx ruff==0.12.4 check --ignore-noqa --config ruff.toml .
```

From the N.E.K.O repository root:

```bash
uv run --with pip python -m plugin.neko_plugin_cli.cli sync neko_tlm --clean
uv run python -m plugin.neko_plugin_cli.cli check neko_tlm
uv run python -m plugin.neko_plugin_cli.cli check -r neko_tlm
```

Python runtime dependencies are declared in `pyproject.toml` and synced into
`vendor/` for packaging. The generated `vendor/` directory is not committed;
local builds and CI recreate it before release checks.

## Market release

Only after the plugin market review is approved, push a tag matching the
`plugin.toml` version to create a GitHub Release asset:

```bash
git tag v1.0.7
git push origin v1.0.7
```

The generated `.github/workflows/release.yml` uploads `neko_tlm.neko-plugin`.
Use that GitHub Release URL when publishing a version in the plugin market.

## Entry

```toml
entry = "plugin.plugins.neko_tlm:NekoMinecraftPlugin"
```
