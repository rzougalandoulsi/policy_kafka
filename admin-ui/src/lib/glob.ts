/**
 * Minimal glob matcher compatible with OPA's glob.match(pattern, [], value) as used by exceptions.rego
 * (no delimiters): `*` matches any run of characters, `?` one character, `[abc]` a class. "*" matches everything.
 */
export function globToRegExp(pattern: string): RegExp {
  let re = '^';
  for (let i = 0; i < pattern.length; i++) {
    const c = pattern[i];
    if (c === '*') {
      // "**" behaves like "*" without delimiters
      while (pattern[i + 1] === '*') i++;
      re += '.*';
    } else if (c === '?') re += '.';
    else if (c === '[') {
      const j = pattern.indexOf(']', i + 1);
      if (j > i) {
        re += '[' + pattern.slice(i + 1, j).replace(/\\/g, '\\\\') + ']';
        i = j;
      } else re += '\\[';
    } else if (c === '{') {
      const j = pattern.indexOf('}', i + 1);
      if (j > i) {
        re +=
          '(?:' +
          pattern
            .slice(i + 1, j)
            .split(',')
            .map(escapeRegExp)
            .join('|') +
          ')';
        i = j;
      } else re += '\\{';
    } else re += escapeRegExp(c);
  }
  return new RegExp(re + '$');
}

export function globMatch(pattern: string, value: string): boolean {
  if (pattern === '*') return true;
  return globToRegExp(pattern).test(value);
}

function escapeRegExp(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
