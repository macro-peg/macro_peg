# Ruby 3 Reference Sources

This directory vendors selected parser/lexer/AST-related source files from the upstream Ruby repository.
They are used as local references while implementing Ruby grammar and AST support in Macro PEG.

## Snapshot

- Upstream repository: https://github.com/ruby/ruby
- Tag: `v3_3_9`
- Commit: `f5c772fc7cbe9f5b58d962939fcb1c7e3fb1cfa6`
- Snapshot directory: `third_party/ruby3/v3_3_9/`

## Included Files

- `parse.y`
- `lex.c.blt`
- `node.h`
- `parser_node.h`
- `rubyparser.h`
- `ast.c`
- `prism/config.yml`
- `prism/node.h`
- `COPYING`

## License

See `third_party/ruby3/v3_3_9/COPYING` for upstream licensing terms.

## Refresh Procedure

1. Clone upstream Ruby with the target tag.
2. Copy parser/lexer/AST files into a new versioned directory under `third_party/ruby3/`.
3. Update this README with tag/commit and file list.
