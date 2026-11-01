import { useState, type KeyboardEvent } from 'react';
import { X } from 'lucide-react';
import { Input } from './form';

/** Free-text multi-value input (Enter or comma adds, Backspace on empty removes last). */
export function ChipsInput({
  id,
  value,
  onChange,
  placeholder,
  suggestions = [],
  disabled,
}: {
  id: string;
  value: string[];
  onChange: (v: string[]) => void;
  placeholder?: string;
  suggestions?: readonly string[];
  disabled?: boolean;
}) {
  const [draft, setDraft] = useState('');
  const add = (raw: string) => {
    const v = raw.trim();
    if (!v || value.includes(v)) return setDraft('');
    onChange([...value, v]);
    setDraft('');
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      add(draft);
    } else if (e.key === 'Backspace' && !draft && value.length) onChange(value.slice(0, -1));
  };
  const listId = `${id}-suggestions`;
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex flex-wrap gap-1">
        {value.map((v) => (
          <span
            key={v}
            className="inline-flex items-center gap-1 rounded-full bg-neutral-bg px-2 py-0.5 text-xs"
          >
            {v}
            {!disabled && (
              <button
                type="button"
                aria-label={`Remove ${v}`}
                onClick={() => onChange(value.filter((x) => x !== v))}
              >
                <X className="h-3 w-3" />
              </button>
            )}
          </span>
        ))}
      </div>
      <Input
        id={id}
        list={suggestions.length ? listId : undefined}
        value={draft}
        placeholder={placeholder}
        disabled={disabled}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={onKey}
        onBlur={() => draft && add(draft)}
      />
      {suggestions.length > 0 && (
        <datalist id={listId}>
          {suggestions
            .filter((s) => !value.includes(s))
            .map((s) => (
              <option key={s} value={s} />
            ))}
        </datalist>
      )}
    </div>
  );
}
