# Shared contract fixture (vendored)

`server-contract.fixtures.json` is the Character Forge BFF's client-contract
pin — the same file hugmesh-character-forge-bff-api tests its Java validator
(`ClientContractFixturesTest`) and its Angular mirror (`server-contract.spec.ts`)
against. The Godot project used to read it across the repo at
`../../frontend/src/app/character-forge/contract/`; now that the project lives
in hugmesh-games that tree is not here, so the pin is VENDORED.

Source: hugmesh-character-forge-bff-api
`frontend/src/app/character-forge/contract/server-contract.fixtures.json`
as of the merge of #22 (2026-08-27). When the BFF's contract changes, copy the
file again — `_test_default_materials_match_the_shared_contract_fixture` in
`tests/run_tests.gd` is what fails when the three copies of the default palette
(this project, the Angular mirror, the Java validator) drift.
