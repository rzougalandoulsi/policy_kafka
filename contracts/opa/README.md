# OPA input/output contract

`input.schema.json` and `output.schema.json` describe contract **2.0** between Governance (`OpaInputAssembler`) and the
policy bundles (`data.governance.core.decision`). Both files are packaged into the `contracts` jar under `opa/` so the
Governance unit test `OpaInputContractTest` validates the assembled input against the same document the policy
repository will validate its fixtures with (Workflow A).

Rules of the road:
- additive changes (new optional fields, new quota keys) bump the minor; renamed or removed fields bump the major;
- a major bump ships Governance first (it must accept both majors, see `governance.opa.supported-contract-majors`),
  then the domain bundles, then core;
- the Rego side declares the version in every bundle's `contract_version`; core refuses to be `ready` when majors differ.
