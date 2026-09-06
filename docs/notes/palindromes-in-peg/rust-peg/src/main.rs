//! A plain PEG evaluator for concrete generated grammar files. No PAL logic,
//! scaffold operations, semantic actions, or input-dependent grammar rules.
use std::collections::HashMap;
use std::env;
use std::fs;
use std::time::Instant;

const MISSING: u32 = u32::MAX;
const BUSY: u32 = u32::MAX - 1;
const FAIL: u32 = u32::MAX;
type MemoRow<const PAGE: usize> = Vec<Option<Box<[u32; PAGE]>>>;

#[derive(Clone, Copy)]
enum Node {
  Empty, Char(char), Any, Ref(u32), Seq(u32, u32), Choice(u32, u32),
  Not(u32), And(u32), Star(u32),
}

struct Grammar {
  nodes: Vec<Node>,
  rules: Vec<u32>,
  start: u32,
}

struct Parser<'a> {
  source: &'a str,
  position: usize,
  names: HashMap<&'a str, u32>,
  nodes: Vec<Node>,
  rules: Vec<u32>,
}

impl<'a> Parser<'a> {
  fn skip(&mut self) {
    while self.position < self.source.len() && self.source.as_bytes()[self.position].is_ascii_whitespace() {
      self.position += 1;
    }
  }

  fn peek(&mut self) -> Option<u8> {
    self.skip();
    self.source.as_bytes().get(self.position).copied()
  }

  fn take(&mut self, token: u8) -> bool {
    if self.peek() == Some(token) { self.position += 1; true } else { false }
  }

  fn expect(&mut self, token: u8) -> Result<(), String> {
    if self.take(token) { Ok(()) } else { Err(format!("expected {:?} at byte {}", token as char, self.position)) }
  }

  fn name(&mut self) -> Result<&'a str, String> {
    self.skip();
    let begin = self.position;
    match self.source.as_bytes().get(begin) {
      Some(c) if c.is_ascii_alphabetic() || *c == b'_' => self.position += 1,
      _ => return Err(format!("expected rule name at byte {}", begin)),
    }
    while let Some(c) = self.source.as_bytes().get(self.position) {
      if !c.is_ascii_alphanumeric() && *c != b'_' { break; }
      self.position += 1;
    }
    Ok(&self.source[begin..self.position])
  }

  fn rule_id(&mut self, name: &'a str) -> Result<u32, String> {
    if let Some(id) = self.names.get(name) { return Ok(*id); }
    let id = u32::try_from(self.rules.len()).map_err(|_| "too many rules")?;
    self.names.insert(name, id);
    self.rules.push(MISSING);
    Ok(id)
  }

  fn node(&mut self, value: Node) -> Result<u32, String> {
    let id = u32::try_from(self.nodes.len()).map_err(|_| "too many expressions")?;
    self.nodes.push(value);
    Ok(id)
  }

  fn literal(&mut self) -> Result<u32, String> {
    self.expect(b'"')?;
    let mut result = None;
    loop {
      let c = self.source[self.position..].chars().next().ok_or("unterminated literal")?;
      self.position += c.len_utf8();
      if c == '"' { break; }
      let c = if c == '\\' {
        let escape = *self.source.as_bytes().get(self.position).ok_or("unterminated escape")?;
        self.position += 1;
        match escape {
          b'"' => '"', b'\\' => '\\', b'/' => '/', b'b' => '\u{8}', b'f' => '\u{c}',
          b'n' => '\n', b'r' => '\r', b't' => '\t',
          b'u' => {
            let end = self.position.checked_add(4).ok_or("invalid Unicode escape")?;
            let text = self.source.get(self.position..end).ok_or("invalid Unicode escape")?;
            let scalar = u32::from_str_radix(text, 16).map_err(|_| "invalid Unicode escape")?;
            self.position = end;
            char::from_u32(scalar).ok_or("surrogate escape is not a scalar")?
          }
          _ => return Err("unsupported literal escape".into()),
        }
      } else {
        if c < ' ' { return Err("unescaped control character in literal".into()); }
        c
      };
      let terminal = self.node(Node::Char(c))?;
      result = Some(match result { None => terminal, Some(left) => self.node(Node::Seq(left, terminal))? });
    }
    match result { Some(id) => Ok(id), None => self.node(Node::Empty) }
  }

  fn prefix(&mut self) -> Result<u32, String> {
    if self.take(b'!') { let child = self.prefix()?; return self.node(Node::Not(child)); }
    if self.take(b'&') { let child = self.prefix()?; return self.node(Node::And(child)); }
    let mut id = if self.take(b'(') {
      let child = self.choice()?; self.expect(b')')?; child
    } else if self.take(b'.') {
      self.node(Node::Any)?
    } else if self.peek() == Some(b'"') {
      self.literal()?
    } else {
      let name = self.name()?;
      let rule = self.rule_id(name)?;
      self.node(Node::Ref(rule))?
    };
    while self.take(b'*') { id = self.node(Node::Star(id))?; }
    Ok(id)
  }

  fn sequence(&mut self) -> Result<u32, String> {
    let mut result = None;
    loop {
      match self.peek() { None | Some(b'/') | Some(b')') | Some(b';') => break, _ => {} }
      let next = self.prefix()?;
      result = Some(match result { None => next, Some(left) => self.node(Node::Seq(left, next))? });
    }
    result.ok_or_else(|| format!("use an explicit empty literal at byte {}", self.position))
  }

  fn choice(&mut self) -> Result<u32, String> {
    let mut result = self.sequence()?;
    while self.take(b'/') { let right = self.sequence()?; result = self.node(Node::Choice(result, right))?; }
    Ok(result)
  }
}

#[derive(Clone, Copy)]
enum Frame {
  Expr(u32, u32), Call(u32, u32), Save(u32, u32), Seq(u32), Choice(u32, u32),
  Predicate(bool, u32), Repeat(u32, u32),
}

#[derive(Debug)]
struct Report { accepted: bool, steps: u64, memo_bytes: usize }

impl Grammar {
  fn parse(source: &str, start: &str) -> Result<Self, String> {
    let mut parser = Parser { source, position: 0, names: HashMap::new(), nodes: Vec::new(), rules: Vec::new() };
    while parser.peek().is_some() {
      let name = parser.name()?;
      let rule = parser.rule_id(name)?;
      if parser.rules[rule as usize] != MISSING { return Err(format!("duplicate rule {name}")); }
      parser.expect(b'=')?;
      let body = parser.choice()?;
      parser.expect(b';')?;
      parser.rules[rule as usize] = body;
    }
    let start = *parser.names.get(start).ok_or("unknown start rule")?;
    for (name, id) in &parser.names {
      if parser.rules[*id as usize] == MISSING { return Err(format!("undefined rule {name}")); }
    }
    Ok(Self { nodes: parser.nodes, rules: parser.rules, start })
  }

  fn matches(&self, word: &[char]) -> Result<Report, String> {
    if word.len() >= BUSY as usize - 1 { return Err("input exceeds evaluator address space".into()); }
    match (word.len() + 1).next_power_of_two().min(64) {
      1 => self.matches_page::<1>(word),
      2 => self.matches_page::<2>(word),
      4 => self.matches_page::<4>(word),
      8 => self.matches_page::<8>(word),
      16 => self.matches_page::<16>(word),
      32 => self.matches_page::<32>(word),
      _ => self.matches_page::<64>(word),
    }
  }

  fn matches_page<const PAGE: usize>(&self, word: &[char]) -> Result<Report, String> {
    let mut memo: Vec<Option<Box<MemoRow<PAGE>>>> = (0..self.rules.len()).map(|_| None).collect();
    let page_count = word.len() / PAGE + 1;
    let mut work = vec![Frame::Call(self.start, 0)];
    let mut out = FAIL;
    let mut steps = 0u64;
    let mut memo_bytes = memo.len() * std::mem::size_of::<Option<Box<MemoRow<PAGE>>>>();
    while let Some(frame) = work.pop() {
      steps += 1;
      match frame {
        Frame::Call(rule, position) => {
          let row = memo[rule as usize].get_or_insert_with(|| {
            memo_bytes += std::mem::size_of::<MemoRow<PAGE>>() + page_count * std::mem::size_of::<Option<Box<[u32; PAGE]>>>();
            Box::new((0..page_count).map(|_| None).collect())
          });
          let page = row[position as usize / PAGE].get_or_insert_with(|| {
            memo_bytes += PAGE * 4;
            Box::new([MISSING; PAGE])
          });
          match page[position as usize % PAGE] {
            MISSING => {
              page[position as usize % PAGE] = BUSY;
              work.push(Frame::Save(rule, position));
              work.push(Frame::Expr(self.rules[rule as usize], position));
            }
            BUSY => return Err(format!("non-consuming recursion: rule {rule}, position {position}")),
            0 => out = FAIL,
            value => out = value - 1,
          }
        }
        Frame::Save(rule, position) => {
          memo[rule as usize].as_mut().unwrap()[position as usize / PAGE].as_mut().unwrap()[position as usize % PAGE] =
            if out == FAIL { 0 } else { out + 1 };
        }
        Frame::Expr(id, position) => match self.nodes[id as usize] {
          Node::Empty => out = position,
          Node::Any => out = if (position as usize) < word.len() { position + 1 } else { FAIL },
          Node::Char(c) => out = if word.get(position as usize) == Some(&c) { position + 1 } else { FAIL },
          Node::Ref(rule) => work.push(Frame::Call(rule, position)),
          Node::Seq(left, right) => { work.push(Frame::Seq(right)); work.push(Frame::Expr(left, position)); }
          Node::Choice(left, right) => { work.push(Frame::Choice(right, position)); work.push(Frame::Expr(left, position)); }
          Node::Not(child) => { work.push(Frame::Predicate(false, position)); work.push(Frame::Expr(child, position)); }
          Node::And(child) => { work.push(Frame::Predicate(true, position)); work.push(Frame::Expr(child, position)); }
          Node::Star(child) => { work.push(Frame::Repeat(child, position)); work.push(Frame::Expr(child, position)); }
        },
        Frame::Seq(right) => if out != FAIL { work.push(Frame::Expr(right, out)); },
        Frame::Choice(right, position) => if out == FAIL { work.push(Frame::Expr(right, position)); },
        Frame::Predicate(positive, position) => out = if (out != FAIL) == positive { position } else { FAIL },
        Frame::Repeat(child, position) => {
          if out == FAIL { out = position; }
          else if out == position { return Err("nullable repetition".into()); }
          else { work.push(Frame::Repeat(child, out)); work.push(Frame::Expr(child, out)); }
        }
      }
    }
    Ok(Report { accepted: out as usize == word.len(), steps, memo_bytes })
  }
}

fn run() -> Result<(), String> {
  let mut args = env::args().skip(1);
  let file = args.next().ok_or("usage: plain-peg-runner FILE [--repeat N] [--start RULE] WORD ...")?;
  let mut repeat = 1usize;
  let mut start = String::from("S");
  let mut words = Vec::new();
  while let Some(arg) = args.next() {
    match arg.as_str() {
      "--repeat" => {
        repeat = args.next().ok_or("missing repeat count")?.parse().map_err(|_| "invalid repeat count")?;
        if repeat == 0 { return Err("repeat count must be positive".into()); }
      }
      "--start" => start = args.next().ok_or("missing start rule")?,
      _ => words.push(arg),
    }
  }
  let began = Instant::now();
  let source = fs::read_to_string(file).map_err(|e| e.to_string())?;
  let grammar = Grammar::parse(&source, &start)?;
  drop(source);
  println!("loaded\trules={}\texpressions={}\tseconds={:.6}", grammar.rules.len(), grammar.nodes.len(), began.elapsed().as_secs_f64());
  for word in words {
    let letters: Vec<char> = word.chars().flat_map(|c| std::iter::repeat(c).take(repeat)).collect();
    let began = Instant::now();
    let report = grammar.matches(&letters)?;
    println!("match\t{:?}\t{}\tseconds={:.6}\tsteps={}\tmemo_bytes={}\tcharacters={}\trepeat={}",
      word, report.accepted, began.elapsed().as_secs_f64(), report.steps, report.memo_bytes, letters.len(), repeat);
  }
  Ok(())
}

fn main() {
  if let Err(message) = run() { eprintln!("error: {message}"); std::process::exit(1); }
}

#[cfg(test)]
mod tests {
  use super::*;

  fn accepted(source: &str, word: &str) -> bool {
    Grammar::parse(source, "S").unwrap().matches(&word.chars().collect::<Vec<_>>()).unwrap().accepted
  }

  #[test]
  fn ordered_choice_commits_and_predicates_restore_position() {
    let source = "S = &(\"abb\") (\"a\" / \"aa\") \"bb\" !.;";
    assert!(accepted(source, "abb"));
    assert!(!accepted(source, "aabb"));
    assert!(!accepted("S = \"a\"* \"a\" !.;", "aa"));
    assert!(accepted("S = !(\"aa\") . !.;", "a"));
  }

  #[test]
  fn recursive_rules_do_not_use_the_host_call_stack() {
    assert!(accepted("S = A !.; A = \"a\" A / \"\";", &"a".repeat(10000)));
    assert!(accepted("S = A !.; A = \"a\" A \"b\" / \"\";", "aaaabbbb"));
    assert!(!accepted("S = A !.; A = \"a\" A \"b\" / \"\";", "aaabb"));
  }

  #[test]
  fn rejects_non_consuming_cycles_and_nullable_repetition() {
    assert!(Grammar::parse("S = A; A = S / \"\";", "S").unwrap().matches(&[]).unwrap_err().contains("non-consuming"));
    assert!(Grammar::parse("S = \"\"*;", "S").unwrap().matches(&[]).unwrap_err().contains("nullable"));
  }

  #[test]
  fn literals_and_rule_errors() {
    assert!(accepted("S = \"あ\\u0008\\\"\\\\\" !.;", "あ\u{8}\"\\"));
    assert!(Grammar::parse("S = X;", "S").is_err());
    assert!(Grammar::parse("S = \"\"; S = .;", "S").is_err());
    assert!(Grammar::parse("S = \"\\ud800\";", "S").is_err());
  }

  #[test]
  fn memo_pages_preserve_results_at_all_size_boundaries() {
    let grammar = Grammar::parse("S = &(A \"b\" !.) A \"b\" !.; A = \"a\" A / \"\";", "S").unwrap();
    for size in [0, 1, 2, 3, 6, 7, 14, 15, 30, 31, 62, 63, 64, 127, 128] {
      for ending in ['b', 'c'] {
        let word: Vec<char> = format!("{}{ending}", "a".repeat(size)).chars().collect();
        let actual = grammar.matches(&word).unwrap();
        let baseline = grammar.matches_page::<64>(&word).unwrap();
        assert_eq!(actual.accepted, ending == 'b');
        assert_eq!(actual.accepted, baseline.accepted);
        assert_eq!(actual.steps, baseline.steps);
        assert!(actual.memo_bytes <= baseline.memo_bytes);
      }
    }
    let short = ['a', 'b'];
    assert!(grammar.matches(&short).unwrap().memo_bytes < grammar.matches_page::<64>(&short).unwrap().memo_bytes);
  }
}
