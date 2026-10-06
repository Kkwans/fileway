# Fileway engineering

- Work on main; preserve unrelated changes. Each verified migration slice gets
  an exact commit and fast-forward push before starting the next slice.
- Repository consolidation is authorized; do not infer authority to erase data,
  replace signing identities or publish an unverified app release.
- Read the closest component rules. Nested CI files are historical source; active
  workflows belong in root .github/workflows with component-specific directories.
- Preserve original module/application IDs during import. Separate structural
  migration, shared-server convergence and product feature work.
- No credentials, databases, personal server addresses or private media in Git.
- Archive old repositories and relocate old local checkouts only after required
  migration gates pass and no running service relies on those directories.
- App feature work remains Plan first, user confirmation, then Goal execution;
  this repository migration does not bypass that product-plan gate.
