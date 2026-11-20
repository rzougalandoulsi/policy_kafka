"""Optional, read-only governance checks and hierarchical topic prefix inventory."""
from __future__ import annotations
import json
import re
from collections import defaultdict
from pathlib import Path

OPS = {'regex', 'not_regex', 'max', 'min', 'equals', 'one_of', 'required'}

def load_rules(path: Path | None):
    if path is None:
        return []
    data = json.loads(path.read_text(encoding='utf-8'))
    rules = data.get('rules')
    if not isinstance(rules, list):
        raise ValueError("Rule config must contain a 'rules' array")
    seen = set()
    for rule in rules:
        if not isinstance(rule, dict) or not all(k in rule for k in ('id','collection','field','operator')):
            raise ValueError('Each rule needs id, collection, field and operator')
        if rule['id'] in seen:
            raise ValueError(f"Duplicate rule id: {rule['id']}")
        seen.add(rule['id'])
        if rule['operator'] not in OPS:
            raise ValueError(f"Unknown operator in rule {rule['id']}: {rule['operator']}")
        if rule['operator'] != 'required' and 'value' not in rule:
            raise ValueError(f"Missing rule value: {rule['id']}")
        if rule['operator'] in ('regex','not_regex'):
            re.compile(rule['value'])
    return rules

def get_field(obj, path):
    value = obj
    for key in path.split('.'):
        if not isinstance(value, dict) or key not in value:
            return None
        value = value[key]
    return value

def violates(rule, value):
    op, target = rule['operator'], rule.get('value')
    if op == 'required':
        return value is None or value == '' or value == []
    if value is None:
        return True  # missing data is never silently accepted
    if op == 'regex':
        return not isinstance(value, str) or re.fullmatch(target, value) is None
    if op == 'not_regex':
        return isinstance(value, str) and re.search(target, value) is not None
    if op == 'max':
        return not isinstance(value, (int,float)) or isinstance(value, bool) or value > target
    if op == 'min':
        return not isinstance(value, (int,float)) or isinstance(value, bool) or value < target
    if op == 'equals':
        return value != target
    if op == 'one_of':
        return value not in target
    raise ValueError(f'Unexpected operator: {op}')

IDENTIFIER_FIELDS = {
    'topics':'topic_name', 'schemas':'subject_name', 'pools':'pool_name',
    'groups_mapping':'group_mapping_display_name', 'services_accounts':'display_name',
    'pools_rbac':'pool_name', 'groups_mapping_rbac':'group_mapping_display_name',
    'services_accounts_rbac':'sa_display_name',
    'topics_meta_datas':'name', 'schemas_meta_datas':'name'
}

def resource_name(collection, item, index):
    return str(item.get(IDENTIFIER_FIELDS.get(collection, 'name'), f'[{index}]'))

def evaluate(parsed, rules, context):
    violations = []
    for rule in rules:
        collection = rule['collection']
        items = parsed.get(collection, [])
        if not isinstance(items, list):
            raise ValueError(f"Configured collection '{collection}' is not a list")
        for i, item in enumerate(items):
            if not isinstance(item, dict):
                raise ValueError(f"Configured collection '{collection}' contains a non-object")
            value = get_field(item, rule['field'])
            if violates(rule, value):
                violations.append({**context, 'collection':collection, 'item_index':i,
                    'resource_name':resource_name(collection,item,i), 'rule_id':rule['id'],
                    'field':rule['field'], 'operator':rule['operator'],
                    'actual_value':json.dumps(value,ensure_ascii=False),
                    'expected':json.dumps(rule.get('value'),ensure_ascii=False),
                    'reason':'missing' if value is None else 'noncompliant'})
    return violations

def distinct_resources(violations):
    return len({(v['repository'],v['file_path'],v['collection'],v['item_index']) for v in violations})

def topic_records(parsed, context):
    topics = []
    for index, item in enumerate(parsed.get('topics', [])):
        name = item.get('topic_name')
        if isinstance(name,str) and name.strip():
            topics.append({**context,'topic_name':name, 'topic_index':index})
    return topics

def candidates(name, delimiter='.'):
    parts = name.split(delimiter)
    return [(i, delimiter.join(parts[:i])+delimiter) for i in range(1,len(parts)) if all(parts[:i])]

def prefix_inventory(topics, preferred_depth=3, delimiter='.'):
    """Scope topic ownership by region+environment. Never imply ownership is granted."""
    by_scope = defaultdict(list)
    by_repo = defaultdict(list)
    for t in topics:
        by_scope[(t['region'],t['environment'])].append(t)
        by_repo[t['repository']].append(t)
    rows = []
    for (repo,region,environment) in sorted({(t['repository'],t['region'],t['environment']) for t in topics}):
        entries = [t for t in by_repo[repo] if (t['region'],t['environment'])==(region,environment)]
        grouped = defaultdict(set)
        for t in entries:
            for depth,prefix in candidates(t['topic_name'],delimiter):
                grouped[(depth,prefix)].add(t['topic_name'])
        for (depth,prefix),names in sorted(grouped.items()):
            overlapping = sorted({other['repository'] for other in by_scope[(region,environment)]
                if other['repository'] != repo and other['topic_name'].startswith(prefix)})
            rows.append({'repository':repo,'region':region,'environment':environment,
                'depth':depth,'prefix':prefix,'topic_count':len(names),
                'cross_repository_overlap':bool(overlapping),
                'other_repositories':';'.join(overlapping),
                'preferred_depth':depth == preferred_depth})
    return rows

def repository_prefix_stats(repo, topics, prefixes, depth):
    items = [t for t in topics if t['repository']==repo]
    preferred = [p for p in prefixes if p['repository']==repo and p['depth']==depth]
    safe = [p for p in preferred if not p['cross_repository_overlap']]
    return {'distinct_topics':len({(t['region'],t['environment'],t['topic_name']) for t in items}),
            'prefix_candidates_at_depth':len(preferred),
            'nonoverlapping_prefixes_at_depth':len(safe),
            'overlapping_prefixes_at_depth':len(preferred)-len(safe),
            'topics_covered_by_nonoverlapping_prefixes':len({(t['region'],t['environment'],t['topic_name']) for t in items
                if any(p['region']==t['region'] and p['environment']==t['environment'] and t['topic_name'].startswith(p['prefix']) for p in safe)})}
