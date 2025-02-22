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

package net.starlark.java.syntax;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Interner;
import com.google.common.collect.Interners;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/** A scanner for Starlark. */
final class Lexer {

  // We intern identifiers and keywords to avoid retaining redundant String objects via the AST.
  //
  // The parser handles interning of string literal values. Benchmarking did not show significant
  // benefit to any further internment. See discussion on Google-internal cl/385193833 for details.
  private static final Interner<String> identInterner = Interners.newWeakInterner();

  // --- These fields are accessed directly by the parser: ---

  // Mapping from file offsets to Locations.
  final FileLocations locs;

  // Information about current token. Updated by nextToken.
  // raw and value are defined only for STRING, BYTE, INT, FLOAT, IDENTIFIER, and COMMENT.
  // TODO(adonovan): rename s/xyz/tokenXyz/
  TokenKind kind;
  int start; // start offset
  int end; // end offset
  Object value; // String, Bytes, Integer/Long/BigInteger, or Double value of token

  // --- end of parser-visible fields ---

  private final List<SyntaxError> errors;

  // Input buffer and position
  private final char[] buffer;
  private int pos;

  private final FileOptions options;

  // The stack of enclosing indentation levels in spaces.
  // The first (outermost) element is always zero.
  private final Stack<Integer> indentStack = new Stack<>();

  private final ImmutableList.Builder<Comment> comments = ImmutableList.builder();

  // The number of unclosed open-parens ("(", '{', '[') at the current point in
  // the stream. Whitespace is handled differently when this is nonzero.
  private int openParenStackDepth = 0;

  // True after a NEWLINE token. In other words, we are outside an
  // expression and we have to check the indentation.
  private boolean checkIndentation;

  // Number of saved INDENT (>0) or OUTDENT (<0) tokens detected but not yet returned.
  private int dents;

  // Characters that can come immediately prior to an '=' character to generate
  // a different token
  private static final ImmutableMap<Character, TokenKind> EQUAL_TOKENS =
      ImmutableMap.<Character, TokenKind>builder()
          .put('=', TokenKind.EQUALS_EQUALS)
          .put('!', TokenKind.NOT_EQUALS)
          .put('>', TokenKind.GREATER_EQUALS)
          .put('<', TokenKind.LESS_EQUALS)
          .put('+', TokenKind.PLUS_EQUALS)
          .put('-', TokenKind.MINUS_EQUALS)
          .put('*', TokenKind.STAR_EQUALS)
          .put('/', TokenKind.SLASH_EQUALS)
          .put('%', TokenKind.PERCENT_EQUALS)
          .put('^', TokenKind.CARET_EQUALS)
          .put('&', TokenKind.AMPERSAND_EQUALS)
          .put('|', TokenKind.PIPE_EQUALS)
          .build();

  // Constructs a lexer which tokenizes the parser input.
  // Errors are appended to errors.
  Lexer(ParserInput input, FileOptions options, List<SyntaxError> errors) {
    this.locs = FileLocations.create(input.getContent(), input.getFile());
    this.options = options;
    this.buffer = input.getContent();
    this.pos = 0;
    this.errors = errors;
    this.checkIndentation = true;
    this.dents = 0;

    indentStack.push(0);
  }

  ImmutableList<Comment> getComments() {
    return comments.build();
  }

  /**
   * Reads the next token, updating the Lexer's token fields. It is an error to call nextToken after
   * an EOF token.
   */
  void nextToken() {
    boolean afterNewline = kind == TokenKind.NEWLINE;
    tokenize();
    Preconditions.checkState(kind != null);

    // Like Python, always end with a NEWLINE token, even if no '\n' in input:
    if (kind == TokenKind.EOF && !afterNewline) {
      kind = TokenKind.NEWLINE;
    }
  }

  private void popParen() {
    if (openParenStackDepth == 0) {
      // TODO(adonovan): fix: the input ')' should not report an indentation error.
      error("indentation error", pos - 1);
    } else {
      openParenStackDepth--;
    }
  }

  private void error(String message, int pos) {
    errors.add(new SyntaxError(locs.getLocation(pos), message));
  }

  private void setToken(TokenKind kind, int start, int end) {
    this.kind = kind;
    this.start = start;
    this.end = end;
    this.value = null;
  }

  // setValue sets the value associated with a STRING, FLOAT, INT,
  // BYTE, IDENTIFIER, or COMMENT token, and records the raw text of the token.
  private void setValue(Object value) {
    this.value = value;
  }

  /** Returns the raw input text associated with the current token. */
  String getRaw() {
    return bufferSlice(start, end);
  }

  /**
   * Parses an end-of-line sequence, handling statement indentation correctly.
   *
   * <p>UNIX newlines are assumed (LF). Carriage returns are always ignored.
   */
  private void newline() {
    if (openParenStackDepth > 0) {
      newlineInsideExpression(); // in an expression: ignore space
    } else {
      checkIndentation = true;
      setToken(TokenKind.NEWLINE, pos - 1, pos);
    }
  }

  private void newlineInsideExpression() {
    while (pos < buffer.length) {
      switch (buffer[pos]) {
        case ' ': case '\t': case '\r':
          pos++;
          break;
        default:
          return;
      }
    }
  }

  /** Computes indentation (updates dent) and advances pos. */
  private void computeIndentation() {
    // we're in a stmt: suck up space at beginning of next line
    int indentLen = 0;
    while (pos < buffer.length) {
      char c = buffer[pos];
      if (c == ' ') {
        indentLen++;
        pos++;
      } else if (c == '\r') {
        pos++;
      } else if (c == '\t') {
        indentLen++;
        pos++;
        error("Tab characters are not allowed for indentation. Use spaces instead.", pos);
      } else if (c == '\n') { // entirely blank line: discard
        indentLen = 0;
        pos++;
      } else if (c == '#') { // line containing only indented comment
        int oldPos = pos;
        while (pos < buffer.length && c != '\n') {
          c = buffer[pos++];
        }
        addComment(oldPos, pos - 1);
        indentLen = 0;
      } else { // printing character
        break;
      }
    }

    if (pos == buffer.length) {
      indentLen = 0;
    } // trailing space on last line

    int peekedIndent = indentStack.peek();
    if (peekedIndent < indentLen) { // push a level
      indentStack.push(indentLen);
      dents++;

    } else if (peekedIndent > indentLen) { // pop one or more levels
      while (peekedIndent > indentLen) {
        indentStack.pop();
        dents--;
        peekedIndent = indentStack.peek();
      }

      if (peekedIndent < indentLen) {
        error("indentation error", pos - 1);
      }
    }
  }

  /**
   * Returns true if current position is in the middle of a triple quote
   * delimiter (3 x quot), and advances 'pos' by two if so.
   */
  private boolean skipTripleQuote(char quot) {
    if (peek(0) == quot && peek(1) == quot) {
      pos += 2;
      return true;
    } else {
      return false;
    }
  }

  /**
   * Scans a string or byte literal delimited by 'quot', containing escape sequences.
   *
   * <p>ON ENTRY: 'pos' is 1 + the index of the first delimiter
   * ON EXIT: 'pos' is 1 + the index of the last delimiter.
   */
  private void escapedLiteral(char quot, boolean isRaw, TokenKind tokenKind) {
    int literalStartPos = isRaw ? pos - 2 : pos - 1;
    boolean inTriplequote = skipTripleQuote(quot);
    // more expensive second choice that expands escaped into a buffer
    StringBuilder literal = new StringBuilder();
    while (pos < buffer.length) {
      char c = buffer[pos];
      pos++;
      switch (c) {
        case '\n':
          if (inTriplequote) {
            literal.append(c);
            break;
          } else {
            error(String.format("unclosed %s", tokenKind.toString()), literalStartPos);
            setToken(tokenKind, literalStartPos, pos);
            setValue(literal.toString());
            return;
          }
        case '\\':
          if (pos == buffer.length) {
            error(String.format("unclosed %s", tokenKind.toString()), literalStartPos);
            setToken(tokenKind, literalStartPos, pos);
            setValue(literal.toString());
            return;
          }
          if (isRaw) {
            // Insert \ and the following character.
            // As in Python, it means that a raw string can never end with a single \.
            literal.append('\\');
            if (peek(0) == '\r' && peek(1) == '\n') {
              literal.append("\n");
              pos += 2;
            } else if (buffer[pos] == '\r' || buffer[pos] == '\n') {
              literal.append("\n");
              pos += 1;
            } else {
              literal.append(buffer[pos]);
              pos += 1;
            }
            break;
          }
          c = buffer[pos];
          pos++;
          switch (c) {
            case '\r':
              if (peek(0) == '\n') {
                pos += 1;
                break;
              } else {
                break;
              }
            case '\n':
              // ignore end of line character
              break;
            case 'n':
              literal.append('\n');
              break;
            case 'r':
              literal.append('\r');
              break;
            case 't':
              literal.append('\t');
              break;
            case '\\':
              literal.append('\\');
              break;
            case '\'':
              literal.append('\'');
              break;
            case '"':
              literal.append('"');
              break;
            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
              { // octal escape
                int octal = c - '0';
                if (pos < buffer.length) {
                  c = buffer[pos];
                  if (c >= '0' && c <= '7') {
                    pos++;
                    octal = (octal << 3) | (c - '0');
                    if (pos < buffer.length) {
                      c = buffer[pos];
                      if (c >= '0' && c <= '7') {
                        pos++;
                        octal = (octal << 3) | (c - '0');
                      }
                    }
                  }
                }
                if(tokenKind.equals(TokenKind.STRING) && octal > 127) {
                  error(String.format(
                    "non-ASCII octal escape \\%o " +
                      "(use \\u%04X for the UTF-8 encoding of U+%04X)",
                    octal, octal, octal),
                    pos-1);
                  setToken(tokenKind, literalStartPos, pos);
                  setValue(literal.toString());
                  break;
                }
                if (octal > 0xff) {
                  error(
                    "octal escape sequence out of range"
                      + " (maximum is \\377)",
                    pos - 1);
                  setToken(tokenKind, literalStartPos, pos);
                  setValue(literal.toString());
                  break;
                }
                literal.append((char)(octal & 0xff));
                break;
              }
            case 'a':
              literal.append('\u0007');
              break;
            case 'b':
              literal.append('\b');
              break;
            case 'f':
              literal.append('\f');
              break;
            case 'v':
              literal.append('\u000B');
              break;
            case 'x': {
              if (pos + 2 >= buffer.length) {
                error(String.format(
                  "truncated escape sequence \\x%s",
                  bufferSlice(pos, buffer.length - 1)),
                  pos - 1);
                setToken(tokenKind, literalStartPos, pos);
                setValue(literal.toString());
                break;
              }
              int n;
              try {
                n = Integer.parseInt(bufferSlice(pos, pos + 2),/*radix*/16);
              } catch (NumberFormatException nfe) {
                error(String.format(
                  "invalid escape sequence \\x%s",
                  bufferSlice(pos, buffer.length - 1)),
                  pos - 1);
                setToken(tokenKind, literalStartPos, pos);
                setValue(literal.toString());
                break;
              }
              if (tokenKind.equals(TokenKind.STRING) && n > Byte.MAX_VALUE) {
                error(
                  String.format("non-ASCII hex escape \\x%s (use \\u%04X for" +
                                  " the UTF-8 encoding of U+%04X)",
                    bufferSlice(pos, pos + 2), n, n), pos - 1);
                setToken(tokenKind, literalStartPos, pos);
                setValue(literal.toString());
                break;
              }
              literal.append(Character.toChars(n));
              pos += 2;
              break;
            }
            case 'u':
            case 'U': {
              int sz = c == 'u' ? 4 : 8;
              if (pos + sz >= buffer.length) {
                error(String.format(
                  "truncated escape sequence \\%c%s",
                  c, bufferSlice(pos, buffer.length - 1)),
                  pos - 1);
                setToken(tokenKind, literalStartPos, pos);
                setValue(literal.toString());
                break;
              }
              int n;
              try {
                n = Integer.parseInt(bufferSlice(pos, pos + sz),/*radix*/16);
              } catch (NumberFormatException nfe) {
                error(String.format(
                  "invalid escape sequence \\%c%s",
                  c, bufferSlice(pos, buffer.length - 1)),
                  pos - 1);
                setToken(tokenKind, literalStartPos, pos);
                setValue(literal.toString());
                break;
              }
              if (n > Character.MAX_CODE_POINT) {
                error(String.format(
                  "code point out of range: \\U%s (max \\U%08x)",
                  bufferSlice(pos, buffer.length - 1), n),
                  pos - 1);
                setToken(tokenKind, literalStartPos, pos);
                setValue(literal.toString());
                break;
              }
              // surrogates are disallowed.
              if (Character.MIN_HIGH_SURROGATE <= n && n < Character.MAX_LOW_SURROGATE) {
                error(String.format("invalid Unicode code point U+%04X", n), pos - 1);
                setToken(tokenKind, literalStartPos, pos);
                setValue(literal.toString());
                break;
              }
              literal.append(Character.toChars(n));
              pos += sz;
              break;
            }
            case 'N':
              // exists in Python but not implemented in Blaze => error
              error("invalid escape sequence: \\" + c, pos - 1);
              break;
            default:
              // unknown char escape => "\literal"
              if (options.restrictStringEscapes()) {
                error(
                    "invalid escape sequence: \\"
                        + c
                        + ". You can enable unknown escape sequences by passing the flag"
                        + " --incompatible_restrict_string_escapes=false",
                    pos - 1);
              }
              literal.append('\\');
              literal.append(c);
              break;
          }
          break;
        case '\'':
        case '"':
          if (c != quot || (inTriplequote && !skipTripleQuote(quot))) {
            // Non-matching quote, treat it like a regular char.
            literal.append(c);
          } else {
            // Matching close-delimiter, all done.
            setToken(tokenKind, literalStartPos, pos);
            setValue(literal.toString());
            return;
          }
          break;
        default:
          literal.append(c);
          break;
      }
    }
    error(String.format("unclosed %s", tokenKind.toString()), literalStartPos);
    setToken(tokenKind, literalStartPos, pos);
    setValue(literal.toString());
  }

  /**
   * Scans a string literal delimited by 'quot'.
   *
   * <ul>
   * <li> ON ENTRY: 'pos' is 1 + the index of the first delimiter
   * <li> ON EXIT: 'pos' is 1 + the index of the last delimiter.
   * </ul>
   *  @param isRaw if true, do not escape the string.
   * @param tokenKind
   */
  private void stringLiteral(char quot, boolean isRaw, TokenKind tokenKind) {
    int literalStartPos = isRaw ? pos - 2 : pos - 1;
    int contentStartPos = pos;

    // Don't even attempt to parse triple-quotes here.
    if (skipTripleQuote(quot)) {
      pos -= 2;
      escapedLiteral(quot, isRaw, tokenKind);
      return;
    }

    // first quick optimistic scan for a simple non-escaped string
    while (pos < buffer.length) {
      char c = buffer[pos++];
      switch (c) {
        case '\n':
          error("unclosed string literal", literalStartPos);
          setToken(tokenKind, literalStartPos, pos);
          setValue(bufferSlice(contentStartPos, pos - 1));
          return;
        case '\\':
          if (isRaw) {
            if (peek(0) == '\r' && peek(1) == '\n') {
              // There was a CRLF after the newline. No shortcut possible, since it needs to be
              // transformed into a single LF.
              pos = contentStartPos;
              escapedLiteral(quot, true, tokenKind);
              return;
            } else {
              pos++;
              break;
            }
          }
          // oops, hit an escape, need to start over & build a new string buffer
          pos = contentStartPos;
          escapedLiteral(quot, false, tokenKind);
          return;
        case '\'':
        case '"':
          if (c == quot) {
            // close-quote, all done.
            setToken(tokenKind, literalStartPos, pos);
            setValue(bufferSlice(contentStartPos, pos - 1));
            return;
          }
          break;
        default: // fall out
      }
    }

    // If the current position is beyond the end of the file, need to move it backwards
    // Possible if the file ends with `r"\` (unclosed raw string literal with a backslash)
    if (pos > buffer.length) {
      pos = buffer.length;
    }

    error("unclosed string literal", literalStartPos);
    setToken(tokenKind, literalStartPos, pos);
    setValue(bufferSlice(contentStartPos, pos));
  }

  private static final Map<String, TokenKind> keywordMap = new HashMap<>();

  static {
    keywordMap.put("and", TokenKind.AND);
    keywordMap.put("as", TokenKind.AS);
    keywordMap.put("assert", TokenKind.ASSERT);
    keywordMap.put("break", TokenKind.BREAK);
    keywordMap.put("class", TokenKind.CLASS);
    keywordMap.put("continue", TokenKind.CONTINUE);
    keywordMap.put("def", TokenKind.DEF);
    keywordMap.put("del", TokenKind.DEL);
    keywordMap.put("elif", TokenKind.ELIF);
    keywordMap.put("else", TokenKind.ELSE);
    keywordMap.put("except", TokenKind.EXCEPT);
    keywordMap.put("finally", TokenKind.FINALLY);
    keywordMap.put("for", TokenKind.FOR);
    keywordMap.put("from", TokenKind.FROM);
    keywordMap.put("global", TokenKind.GLOBAL);
    keywordMap.put("if", TokenKind.IF);
    keywordMap.put("import", TokenKind.IMPORT);
    keywordMap.put("in", TokenKind.IN);
    keywordMap.put("is", TokenKind.IS);
    keywordMap.put("lambda", TokenKind.LAMBDA);
    keywordMap.put("load", TokenKind.LOAD);
    keywordMap.put("nonlocal", TokenKind.NONLOCAL);
    keywordMap.put("not", TokenKind.NOT);
    keywordMap.put("or", TokenKind.OR);
    keywordMap.put("pass", TokenKind.PASS);
    keywordMap.put("raise", TokenKind.RAISE);
    keywordMap.put("return", TokenKind.RETURN);
    keywordMap.put("try", TokenKind.TRY);
    keywordMap.put("while", TokenKind.WHILE);
    keywordMap.put("with", TokenKind.WITH);
    keywordMap.put("yield", TokenKind.YIELD);
  }

  /**
   * Scans an identifier or keyword.
   *
   * <p>ON ENTRY: 'pos' is 1 + the index of the first char in the identifier.
   * ON EXIT: 'pos' is 1 + the index of the last char in the identifier.
   */
  private void identifierOrKeyword() {
    int oldPos = pos - 1;
    String id = identInterner.intern(scanIdentifier());
    TokenKind kind = keywordMap.get(id);
    if (kind == null) {
      setToken(TokenKind.IDENTIFIER, oldPos, pos);
      // setValue allocates a new String for the raw text, but it's not retained so we don't bother
      // interning it.
      setValue(id);
    } else {
      setToken(kind, oldPos, pos);
    }
  }

  private String scanIdentifier() {
    // Keep consistent with Identifier.isValid.
    // TODO(laurentlb): Handle Unicode letters.
    int oldPos = pos - 1;
    while (pos < buffer.length) {
      switch (buffer[pos]) {
        case '_':
        case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
        case 'g': case 'h': case 'i': case 'j': case 'k': case 'l':
        case 'm': case 'n': case 'o': case 'p': case 'q': case 'r':
        case 's': case 't': case 'u': case 'v': case 'w': case 'x':
        case 'y': case 'z':
        case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
        case 'G': case 'H': case 'I': case 'J': case 'K': case 'L':
        case 'M': case 'N': case 'O': case 'P': case 'Q': case 'R':
        case 'S': case 'T': case 'U': case 'V': case 'W': case 'X':
        case 'Y': case 'Z':
        case '0': case '1': case '2': case '3': case '4': case '5':
        case '6': case '7': case '8': case '9':
          pos++;
          break;
       default:
          return bufferSlice(oldPos, pos);
      }
    }
    return bufferSlice(oldPos, pos);
  }

  /**
   * Tokenizes a two-char operator.
   * @return true if it tokenized an operator
   */
  private boolean tokenizeTwoChars() {
    if (pos + 2 >= buffer.length) {
      return false;
    }
    char c1 = buffer[pos];
    char c2 = buffer[pos + 1];
    TokenKind tok = null;
    if (c2 == '=') {
      tok = EQUAL_TOKENS.get(c1);
    } else if (c2 == '*' && c1 == '*') {
      tok = TokenKind.STAR_STAR;
    }
    if (tok == null) {
      return false;
    } else {
      setToken(tok, pos, pos + 2);
      return true;
    }
  }

  // Returns the ith unconsumed char, or -1 for EOF.
  private int peek(int i) {
    return pos + i < buffer.length ? buffer[pos + i] : -1;
  }

  // Consumes a char and returns the next unconsumed char, or -1 for EOF.
  private int next() {
    pos++;
    return peek(0);
  }

  /**
   * Performs tokenization of the character buffer of file contents provided to the constructor. At
   * least one token will be added to the tokens queue.
   */
  private void tokenize() {
    if (checkIndentation) {
      checkIndentation = false;
      computeIndentation();
    }

    // Return saved indentation tokens.
    if (dents != 0) {
      if (dents < 0) {
        dents++;
        setToken(TokenKind.OUTDENT, pos - 1, pos);
      } else {
        dents--;
        setToken(TokenKind.INDENT, pos - 1, pos);
      }
      return;
    }

    // TODO(adonovan): cleanup: replace break after setToken with return,
    // and eliminate null-check of this.kind.
    kind = null;
    while (pos < buffer.length) {
      if (tokenizeTwoChars()) {
        pos += 2;
        return;
      }
      char c = buffer[pos];
      pos++;
      switch (c) {
        case '{':
          setToken(TokenKind.LBRACE, pos - 1, pos);
          openParenStackDepth++;
          break;
        case '}':
          setToken(TokenKind.RBRACE, pos - 1, pos);
          popParen();
          break;
        case '(':
          setToken(TokenKind.LPAREN, pos - 1, pos);
          openParenStackDepth++;
          break;
        case ')':
          setToken(TokenKind.RPAREN, pos - 1, pos);
          popParen();
          break;
        case '[':
          setToken(TokenKind.LBRACKET, pos - 1, pos);
          openParenStackDepth++;
          break;
        case ']':
          setToken(TokenKind.RBRACKET, pos - 1, pos);
          popParen();
          break;
        case '>':
          if (peek(0) == '>' && peek(1) == '=') {
            setToken(TokenKind.GREATER_GREATER_EQUALS, pos - 1, pos + 2);
            pos += 2;
          } else if (peek(0) == '>') {
            setToken(TokenKind.GREATER_GREATER, pos - 1, pos + 1);
            pos += 1;
          } else {
            setToken(TokenKind.GREATER, pos - 1, pos);
          }
          break;
        case '<':
          if (peek(0) == '<' && peek(1) == '=') {
            setToken(TokenKind.LESS_LESS_EQUALS, pos - 1, pos + 2);
            pos += 2;
          } else if (peek(0) == '<') {
            setToken(TokenKind.LESS_LESS, pos - 1, pos + 1);
            pos += 1;
          } else {
            setToken(TokenKind.LESS, pos - 1, pos);
          }
          break;
        case ':':
          setToken(TokenKind.COLON, pos - 1, pos);
          break;
        case ',':
          setToken(TokenKind.COMMA, pos - 1, pos);
          break;
        case '+':
          setToken(TokenKind.PLUS, pos - 1, pos);
          break;
        case '-':
          setToken(TokenKind.MINUS, pos - 1, pos);
          break;
        case '|':
          setToken(TokenKind.PIPE, pos - 1, pos);
          break;
        case '=':
          setToken(TokenKind.EQUALS, pos - 1, pos);
          break;
        case '%':
          setToken(TokenKind.PERCENT, pos - 1, pos);
          break;
        case '~':
          setToken(TokenKind.TILDE, pos - 1, pos);
          break;
        case '&':
          setToken(TokenKind.AMPERSAND, pos - 1, pos);
          break;
        case '^':
          setToken(TokenKind.CARET, pos - 1, pos);
          break;
        case '/':
          if (peek(0) == '/' && peek(1) == '=') {
            setToken(TokenKind.SLASH_SLASH_EQUALS, pos - 1, pos + 2);
            pos += 2;
          } else if (peek(0) == '/') {
            setToken(TokenKind.SLASH_SLASH, pos - 1, pos + 1);
            pos += 1;
          } else {
            // /= is handled by tokenizeTwoChars.
            setToken(TokenKind.SLASH, pos - 1, pos);
          }
          break;
        case ';':
          setToken(TokenKind.SEMI, pos - 1, pos);
          break;
        case '*':
          setToken(TokenKind.STAR, pos - 1, pos);
          break;
        case ' ':
        case '\t':
        case '\r':
          /* ignore */
          break;
        case '\\':
          // Backslash character is valid only at the end of a line (or in a string)
          if (peek(0) == '\n') {
            pos += 1; // skip the end of line character
          } else if (peek(0) == '\r' && peek(1) == '\n') {
            pos += 2; // skip the CRLF at the end of line
          } else {
            setToken(TokenKind.ILLEGAL, pos - 1, pos);
            setValue(Character.toString(c));
          }
          break;
        case '\n':
          newline();
          break;
        case '#':
          int oldPos = pos - 1;
          while (pos < buffer.length) {
            c = buffer[pos];
            if (c == '\n') {
              break;
            } else {
              pos++;
            }
          }
          addComment(oldPos, pos);
          break;
        case '\'':
        case '\"':
          stringLiteral(c, false, TokenKind.STRING);
          break;
        default:
          // detect raw strings, e.g. r"str" or b".."
          if (c == 'r' || c == 'b') {
            int c0 = peek(0);
            if (c0 == '\'' || c0 == '\"') {
              pos++;
              stringLiteral((char) c0, c == 'r', c == 'r' ? TokenKind.STRING : TokenKind.BYTE);
              break;
            }
            else if (c == 'r' && c0 == 'b' && (buffer.length > 2)) {
              int c1 = peek(1);
              // rb"..."
              if(c1 == '"' || c1 == '\'') {
                pos+=2;
                stringLiteral((char) c1, true, TokenKind.BYTE);
                break;
              }
            }
          }

          // int or float literal, or dot
          if (c == '.' || isdigit(c)) {
            pos--; // unconsume
            scanNumberOrDot(c);
            break;
          }

          if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_') {
            identifierOrKeyword();
          } else {
            error("invalid character: '" + c + "'", pos - 1);
          }
          break;
      } // switch
      if (kind != null) { // stop here if we scanned a token
        return;
      }
    } // while

    if (indentStack.size() > 1) { // top of stack is always zero
      setToken(TokenKind.NEWLINE, pos - 1, pos);
      while (indentStack.size() > 1) {
        indentStack.pop();
        dents--;
      }
      return;
    }

    setToken(TokenKind.EOF, pos, pos);
  }

  // Scans a number (INT or FLOAT) or DOT.
  // Precondition: c == peek(0) (a dot or digit)
  //
  // TODO(adonovan): make this the precondition for all scan functions;
  // currenly most assume their argument c has been consumed already.
  private void scanNumberOrDot(int c) {
    int start = this.pos;
    boolean fraction = false;
    boolean exponent = false;

    if (c == '.') {
      // dot or start of fraction
      if (!isdigit(peek(1))) {
        pos++; // consume '.'
        setToken(TokenKind.DOT, start, pos);
        return;
      }
      fraction = true;

    } else if (c == '0') {
      // hex, octal, binary or float
      c = next();
      if (c == '.') {
        fraction = true;

      } else if (c == 'x' || c == 'X') {
        // hex
        c = next();
        if (!isxdigit(c)) {
          error("invalid hex literal", start);
        }
        while (isxdigit(c)) {
          c = next();
        }

      } else if (c == 'o' || c == 'O') {
        // octal
        c = next();
        while (isdigit(c)) {
          c = next();
        }

      } else if (c == 'b' || c == 'B') {
        // binary
        c = next();
        if (!isbdigit(c)) {
          error("invalid binary literal", start);
        }
        while (isbdigit(c)) {
          c = next();
        }

      } else {
        // "0" or float or obsolete octal "0755"
        while (isdigit(c)) {
          c = next();
        }
        if (c == '.') {
          fraction = true;
        } else if (c == 'e' || c == 'E') {
          exponent = true;
        }
      }

    } else {
      // decimal
      while (isdigit(c)) {
        c = next();
      }
      if (c == '.') {
        fraction = true;
      } else if (c == 'e' || c == 'E') {
        exponent = true;
      }
    }

    if (fraction) {
      c = next(); // consume '.'
      while (isdigit(c)) {
        c = next();
      }

      if (c == 'e' || c == 'E') {
        exponent = true;
      }
    }

    if (exponent) {
      c = next(); // consume [eE]
      if (c == '+' || c == '-') {
        c = next();
      }
      while (isdigit(c)) {
        c = next();
      }
    }

    // float?
    if (fraction || exponent) {
      setToken(TokenKind.FLOAT, start, pos);
      double value = 0.0;
      try {
        value = Double.parseDouble(bufferSlice(start, pos));
        if (!Double.isFinite(value)) {
          error("floating-point literal too large", start);
        }
      } catch (NumberFormatException ex) {
        error("invalid float literal", start);
      }
      setValue(value);
      return;
    }

    // int
    setToken(TokenKind.INT, start, pos);
    String literal = bufferSlice(start, pos);
    Number value = 0;
    try {
      value = IntLiteral.scan(literal);
    } catch (NumberFormatException ex) {
      error(ex.getMessage(), start);
    }
    setValue(value);
  }

  private static boolean isdigit(int c) {
    return '0' <= c && c <= '9';
  }

  private static boolean isxdigit(int c) {
    return isdigit(c) || ('A' <= c && c <= 'F') || ('a' <= c && c <= 'f');
  }

  private static boolean isbdigit(int c) {
    return c == '0' || c == '1';
  }

  /*
   * Returns a string containing the part of the source buffer beginning at offset {@code start} and
   * ending immediately before offset {@code end} (so the length of the resulting string is {@code
   * end - start}).
   */
  String bufferSlice(int start, int end) {
    return new String(this.buffer, start, end - start);
  }

  // TODO(adonovan): don't retain comments unconditionally.
  private void addComment(int start, int end) {
    String content = bufferSlice(start, end);
    comments.add(new Comment(locs, start, content));
  }
}
