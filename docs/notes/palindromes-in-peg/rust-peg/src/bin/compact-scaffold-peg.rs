//! Ordinary PEG nonterminal inlining for the generated S/B_n/P_n/E_n format.
//! The pass never runs the source machine or interprets input words.
use std::borrow::Cow;
use std::env;
use std::fs::{self, File};
use std::io::{BufWriter, Write};
use std::path::Path;
use std::time::Instant;

const ABSENT: usize = usize::MAX;
const DEPTH: u8 = 16;
const DIGITS: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

#[derive(Clone, Copy)]
struct Entry { begin: usize, end: usize, uses: u32 }
impl Default for Entry {
  fn default() -> Self { Self { begin: ABSENT, end: 0, uses: 0 } }
}

fn code(name: &[u8]) -> Result<u32, String> {
  if name == b"S" { return Ok(0); }
  let group = match name.first() {
    Some(b'B') => 1, Some(b'P') => 2, Some(b'E') => 3,
    _ => return Err("expected a scaffold rule identifier".into()),
  };
  if name.len() < 3 || name[1] != b'_' { return Err("expected a numbered rule identifier".into()); }
  let mut index = 0u32;
  for digit in &name[2..] {
    if !digit.is_ascii_digit() { return Err("invalid rule number".into()); }
    index = index.checked_mul(10).and_then(|n| n.checked_add((digit - b'0') as u32))
      .ok_or("rule number overflow")?;
  }
  index.checked_mul(4).and_then(|n| n.checked_add(group)).ok_or("rule number overflow".into())
}

fn next_ref(body: &[u8], position: &mut usize) -> Result<Option<(usize, usize, u32)>, String> {
  while *position < body.len() {
    let c = body[*position];
    if c == b'"' {
      *position += 1;
      loop {
        let c = *body.get(*position).ok_or("unterminated literal")?;
        *position += 1;
        if c == b'\\' {
          if *position == body.len() { return Err("unterminated escape".into()); }
          *position += 1;
        } else if c == b'"' { break; }
      }
    } else if c.is_ascii_alphabetic() || c == b'_' {
      let begin = *position;
      *position += 1;
      while *position < body.len() &&
          (body[*position].is_ascii_alphanumeric() || body[*position] == b'_') { *position += 1; }
      return Ok(Some((begin, *position, code(&body[begin..*position])?)));
    } else { *position += 1; }
  }
  Ok(None)
}

fn trim_end(mut bytes: &[u8]) -> &[u8] {
  while bytes.last().is_some_and(u8::is_ascii_whitespace) { bytes = &bytes[..bytes.len() - 1]; }
  bytes
}

fn needs_group(body: &[u8], before: &[u8], after: &[u8]) -> bool {
  if matches!(trim_end(before).last(), Some(b'!') | Some(b'&')) ||
      after.iter().find(|c| !c.is_ascii_whitespace()) == Some(&b'*') { return true; }
  let (mut position, mut depth) = (0, 0usize);
  while position < body.len() {
    match body[position] {
      b'"' => {
        position += 1;
        while position < body.len() {
          if body[position] == b'\\' { position += 2; }
          else if body[position] == b'"' { break; }
          else { position += 1; }
        }
      }
      b'(' => depth += 1,
      b')' => depth = depth.saturating_sub(1),
      b'/' if depth == 0 => return true,
      _ => {}
    }
    position += 1;
  }
  false
}

fn name(id: u32) -> Vec<u8> {
  if id == 0 { return b"S".to_vec(); }
  let mut value = id >> 2;
  let mut result = Vec::with_capacity(8);
  loop {
    result.push(DIGITS[(value % 62) as usize]);
    value /= 62;
    if value == 0 { break; }
  }
  result.push(b"sbpe"[(id & 3) as usize]);
  result.reverse();
  result
}

struct Index { source: Vec<u8>, entries: [Vec<Entry>; 4], count: usize }

fn entry_mut(entries: &mut [Vec<Entry>; 4], id: u32) -> &mut Entry {
  let table = &mut entries[(id & 3) as usize];
  let index = (id >> 2) as usize;
  if table.len() <= index { table.resize(index + 1, Entry::default()); }
  &mut table[index]
}

impl Index {
  fn parse(source: Vec<u8>) -> Result<Self, String> {
    let mut entries: [Vec<Entry>; 4] = std::array::from_fn(|_| Vec::new());
    let (mut offset, mut count) = (0, 0);
    for line in source.split_inclusive(|c| *c == b'\n') {
      let clean = trim_end(line);
      let equals = clean.windows(3).position(|part| part == b" = ").ok_or("expected one production per line")?;
      if clean.last() != Some(&b';') { return Err("missing production terminator".into()); }
      let id = code(&clean[..equals])?;
      let entry = entry_mut(&mut entries, id);
      if entry.begin != ABSENT { return Err("duplicate production".into()); }
      entry.begin = offset + equals + 3;
      entry.end = offset + clean.len() - 1;
      let body = &source[entry.begin..entry.end];
      let mut position = 0;
      while let Some((_, _, child)) = next_ref(body, &mut position)? {
        let child = entry_mut(&mut entries, child);
        child.uses = child.uses.checked_add(1).ok_or("reference count overflow")?;
      }
      offset += line.len();
      count += 1;
      if count % 10_000_000 == 0 { eprintln!("indexed {count} productions"); }
    }
    if entries[0].first().is_none_or(|entry| entry.begin == ABSENT) {
      return Err("missing S production".into());
    }
    if entries.iter().flatten().any(|entry| entry.begin == ABSENT && entry.uses != 0) {
      return Err("undefined production".into());
    }
    Ok(Self { source, entries, count })
  }

  fn entry(&self, id: u32) -> &Entry { &self.entries[(id & 3) as usize][(id >> 2) as usize] }
  fn body(&self, id: u32) -> &[u8] {
    let entry = self.entry(id);
    &self.source[entry.begin..entry.end]
  }
  fn private(&self, id: u32, depth: u8) -> bool {
    id & 3 == 3 && depth < DEPTH && self.entry(id).uses == 1
  }

  // The same private two-branch fusion as the Python reference, before
  // bounded inlining. Literals are never treated as reference names.
  fn rewritten(&self, id: u32) -> Cow<'_, [u8]> {
    let original = self.body(id);
    let words: Vec<_> = original.split(|c| c.is_ascii_whitespace()).filter(|x| !x.is_empty()).collect();
    if words.len() != 3 || words[1] != b"/" { return Cow::Borrowed(original); }
    let branches: Option<Vec<_>> = [words[0], words[2]].into_iter().map(|word| {
      let child = code(word).ok()?;
      if child & 3 != 3 || self.entry(child).uses != 1 { return None; }
      let pair: Vec<_> = self.body(child).split(|c| c.is_ascii_whitespace()).filter(|x| !x.is_empty()).collect();
      if pair.len() != 2 || pair.iter().any(|word| code(word).map_or(true, |id| id & 3 != 3)) { return None; }
      Some((pair[0], pair[1]))
    }).collect();
    let Some(branches) = branches else { return Cow::Borrowed(original); };
    let mut output = Vec::new();
    for (i, (mut guard, value)) in branches.into_iter().enumerate() {
      if i != 0 { output.extend_from_slice(b" / "); }
      let guard_id = code(guard).unwrap();
      let guard_body = self.body(guard_id);
      if self.entry(guard_id).uses == 1 && guard_body.first() == Some(&b'!') &&
          code(&guard_body[1..]).is_ok_and(|id| id & 3 == 3) { guard = guard_body; }
      output.extend_from_slice(guard);
      output.push(b' ');
      output.extend_from_slice(value);
    }
    Cow::Owned(output)
  }

  fn live(&self) -> Result<[Vec<bool>; 4], String> {
    let mut live: [Vec<bool>; 4] = std::array::from_fn(|i| vec![false; self.entries[i].len()]);
    let mut work = vec![0u32];
    let mut roots = 0;
    while let Some(id) = work.pop() {
      let saved = &mut live[(id & 3) as usize][(id >> 2) as usize];
      if *saved { continue; }
      *saved = true;
      roots += 1;
      let mut frames = vec![Frame::new(self.rewritten(id), 0, false)];
      while let Some(frame) = frames.last_mut() {
        if let Some((_, _, child)) = next_ref(&frame.body, &mut frame.position)? {
          if self.private(child, frame.depth) {
            let depth = frame.depth + 1;
            frames.push(Frame::new(self.rewritten(child), depth, false));
          } else { work.push(child); }
        } else { frames.pop(); }
      }
      if roots % 1_000_000 == 0 { eprintln!("reached {roots} retained productions"); }
    }
    Ok(live)
  }

  fn expanded(&self, id: u32, output: &mut impl Write) -> Result<(), String> {
    let mut frames = vec![Frame::new(self.rewritten(id), 0, false)];
    while let Some(frame) = frames.last_mut() {
      if let Some((begin, end, child)) = next_ref(&frame.body, &mut frame.position)? {
        output.write_all(&frame.body[frame.copied..begin]).map_err(|e| e.to_string())?;
        frame.copied = end;
        if self.private(child, frame.depth) {
          let body = self.rewritten(child);
          let closing = needs_group(&body, &frame.body[..begin], &frame.body[end..]);
          let depth = frame.depth + 1;
          if closing { output.write_all(b"(").map_err(|e| e.to_string())?; }
          frames.push(Frame::new(body, depth, closing));
        } else { output.write_all(&name(child)).map_err(|e| e.to_string())?; }
      } else {
        output.write_all(&frame.body[frame.copied..]).map_err(|e| e.to_string())?;
        if frame.closing { output.write_all(b")").map_err(|e| e.to_string())?; }
        frames.pop();
      }
    }
    Ok(())
  }

  fn emit(&self, live: &[Vec<bool>; 4], output: &mut impl Write) -> Result<usize, String> {
    let mut count = 0;
    for (group, table) in live.iter().enumerate() {
      for (index, used) in table.iter().enumerate() {
        if !used { continue; }
        let id = (index as u32) * 4 + group as u32;
        output.write_all(&name(id)).map_err(|e| e.to_string())?;
        output.write_all(b" = ").map_err(|e| e.to_string())?;
        self.expanded(id, output)?;
        output.write_all(b";\n").map_err(|e| e.to_string())?;
        count += 1;
        if count % 1_000_000 == 0 { eprintln!("wrote {count} productions"); }
      }
    }
    Ok(count)
  }
}

struct Frame<'a> { body: Cow<'a, [u8]>, position: usize, copied: usize, depth: u8, closing: bool }
impl<'a> Frame<'a> {
  fn new(body: Cow<'a, [u8]>, depth: u8, closing: bool) -> Self {
    Self { body, position: 0, copied: 0, depth, closing }
  }
}

fn run(source: &Path, target: &Path) -> Result<(), String> {
  let start = Instant::now();
  let source_path = fs::canonicalize(source).map_err(|e| e.to_string())?;
  let mut temporary = target.as_os_str().to_os_string();
  temporary.push(".partial");
  let temporary = Path::new(&temporary);
  for path in [target, temporary] {
    if fs::canonicalize(path).is_ok_and(|path| path == source_path) {
      return Err("source and output must be distinct".into());
    }
  }
  let index = Index::parse(fs::read(source).map_err(|e| e.to_string())?)?;
  eprintln!("indexed {} productions in {:.2}s", index.count, start.elapsed().as_secs_f64());
  let live = index.live()?;
  eprintln!("reachability complete in {:.2}s", start.elapsed().as_secs_f64());
  let mut output = BufWriter::with_capacity(1 << 20, File::create(temporary).map_err(|e| e.to_string())?);
  let count = index.emit(&live, &mut output)?;
  output.flush().map_err(|e| e.to_string())?;
  drop(output);
  fs::rename(temporary, target).map_err(|e| e.to_string())?;
  println!("compacted\tbefore_rules={}\tafter_rules={}\tbefore_bytes={}\tafter_bytes={}\tseconds={:.3}",
    index.count, count, index.source.len(), fs::metadata(target).map_err(|e| e.to_string())?.len(),
    start.elapsed().as_secs_f64());
  Ok(())
}

fn main() {
  let args: Vec<_> = env::args_os().skip(1).collect();
  let result = if args.len() == 2 { run(Path::new(&args[0]), Path::new(&args[1])) }
    else { Err("usage: compact-scaffold-peg SOURCE TARGET".into()) };
  if let Err(error) = result { eprintln!("{error}"); std::process::exit(1); }
}

#[cfg(test)]
mod tests {
  use super::*;

  fn compact(text: &str) -> String {
    let index = Index::parse(text.as_bytes().to_vec()).unwrap();
    let live = index.live().unwrap();
    let mut output = Vec::new();
    index.emit(&live, &mut output).unwrap();
    String::from_utf8(output).unwrap()
  }

  #[test]
  fn identifiers_ignore_quoted_names_and_escapes() {
    let source = "S = E_0 !.;\nE_0 = \"E_999\\\"x\";\n";
    let output = compact(source);
    assert!(output.contains("\"E_999\\\"x\""));
    assert_eq!(output.lines().count(), 1);
    assert!(Index::parse(b"S = E_0;\n".to_vec()).is_err());
  }

  #[test]
  fn aliases_keep_scope_below_predicates_and_repetition() {
    assert_eq!(compact("S = !E_0 .* !.;\nE_0 = E_1;\nE_1 = \"a\" \"b\";\n"),
      "S = !(\"a\" \"b\") .* !.;\n");
    assert_eq!(compact("S = E_0* !.;\nE_0 = E_1;\nE_1 = \"a\" \"b\";\n"),
      "S = (\"a\" \"b\")* !.;\n");
  }

  #[test]
  fn shared_expressions_and_recursive_boundaries_remain() {
    let output = compact("S = B_0 !.;\nB_0 = E_0 B_0 / E_0;\nE_0 = \"a\";\n");
    assert!(output.contains("b0 = e0 b0 / e0;"));
    assert!(output.contains("e0 = \"a\";"));
    assert_eq!(name(code(b"B_3519").unwrap()), b"bUL");
  }

  #[test]
  fn deep_private_chains_leave_bounded_references() {
    let mut source = "S = E_0;\n".to_owned();
    for n in 0..1000 { source += &format!("E_{n} = E_{};\n", n + 1); }
    source += "E_1000 = \"\";\n";
    let output = compact(&source);
    assert!(output.lines().count() > 1 && output.lines().count() < 100);
  }
}
