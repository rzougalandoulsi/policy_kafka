import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[1] / 'governance.py'
spec = importlib.util.spec_from_file_location('gov_test',SRC)
gov = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gov)

class GovernanceTests(unittest.TestCase):
    def test_distinct_resources_vs_violations(self):
        rules = [
            {'id':'name', 'collection':'topics', 'field':'topic_name','operator':'regex','value':r'^[a-z]+(\.[a-z]+)+$'},
            {'id':'parts','collection':'topics','field':'partition_count','operator':'max','value':6},
        ]
        context = dict(repository='r',file_path='cacn/nprod/input.tfvars',region='cacn',environment='nprod')
        findings=gov.evaluate({'topics':[{'topic_name':'BAD','partition_count':20},
                                         {'topic_name':'good.topic','partition_count':3}]},rules,context)
        self.assertEqual(len(findings),2)
        self.assertEqual(gov.distinct_resources(findings),1)
    def test_prefix_scoping_and_overlaps(self):
        def t(repo, name, env='nprod'):
            return dict(repository=repo, topic_name=name, region='cacn',environment=env)
        topics = [t('r1','sec.trace.tau.send.dev'), t('r1','sec.trace.tau.read.dev'),
                  t('r2','sec.trace.foo.send.dev'),t('r3','sec.trace.foo.send.dev','prod')]
        rows = gov.prefix_inventory(topics,3)
        r1d2 = [r for r in rows if r['repository']=='r1' and r['depth']==2]
        self.assertEqual(len(r1d2),1)
        self.assertTrue(r1d2[0]['cross_repository_overlap'])
        r1d3 = [r for r in rows if r['repository']=='r1' and r['depth']==3]
        self.assertEqual(len(r1d3),1)
        self.assertFalse(r1d3[0]['cross_repository_overlap'])
        stats=gov.repository_prefix_stats('r1',topics,rows,3)
        self.assertEqual(stats['topics_covered_by_nonoverlapping_prefixes'],2)
        self.assertEqual(stats['nonoverlapping_prefixes_at_depth'],1)
    def test_rules_config_and_missing_fields(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'rules.json'
            p.write_text(json.dumps({'rules':[{'id':'required','collection':'pools',
                           'field':'pool_name','operator':'required'}]}))
            rules=gov.load_rules(p)
            self.assertEqual(len(gov.evaluate({'pools':[{}]},rules,{'repository':'r','file_path':'x'})),1)
            self.assertFalse(gov.violates(rules[0],'value'))
            self.assertEqual(gov.load_rules(None),[])

if __name__=='__main__':
    unittest.main()
