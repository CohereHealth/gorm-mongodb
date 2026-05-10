# KOZA-527: Branch Setup Summary

## Branches Created

### 1. feat/native-mongodb-transactions-8.1
- **Base**: upstream/8.1.x (clean copy)
- **Purpose**: Reference branch showing the clean 8.1.x state
- **Versions**:
  - projectVersion: 8.1.3-SNAPSHOT
  - datastoreVersion: 8.0.4 ✅
  - grailsVersion: 6.0.0
  - groovyVersion: 3.0.11
- **Status**: Clean, ready for reference
- **Commits**: Matches upstream/8.1.x exactly

### 2. feat/native-mongodb-transactions-8.1_initial_solution
- **Base**: feat/native-mongodb-transactions-8.1
- **Purpose**: Working branch with all native transaction commits applied
- **Commits Applied**: 34 commits (32 from feat/native-mongodb-transactions + 2 CI/test commits)
- **Status**: Ready for testing and PR
- **Conflict Resolution**: 1 conflict in `.github/workflows/gradle.yml` (resolved - kept CodeArtifact publishing)

## Commits Successfully Cherry-Picked (34 total)

All commits from `feat/native-mongodb-transactions` have been successfully applied:

1. feat(config): add nativeTransactions configuration setting
2. feat(context): add thread-local native transaction context manager
3. feat(transaction): add MongoTransactionObject and MongoSessionHolder
4. feat(session): add native codec session and entity persister
5. feat(tx-manager): add transaction managers for native MongoDB transactions
6. feat(api): add native transaction API layer
7. feat(core): integrate native transactions into datastore and sessions
8. feat(bulk): add bulk write operations with transaction support
9. feat(support): add supporting classes for native transactions
10. test: add test suites for native transactions and bulk operations
11. feat(example): add mongodb-native-session example project
12. docs: add documentation for native transactions and bulk operations
13. test(native-session): update native transaction integration tests
14. fix(mongo-transactions): ensure CRUD operations, queries, and rollback work
15. docs: add detailed javadoc to native transaction classes
16. test: add PatientServiceIntegrationSpec for standard Spring-managed transactions
17. fix(flush): restore optimistic locking counter in MongoCodecSession
18. fix: restore hasTransaction() to always return true matching 8.2.x behaviour
19. fix: override hasTransaction in MongoNativeCodecSession to check native context
20. feat(native-tx): add tests for criteria, aggregation, associations, embedded, text search
21. refactor(native-tx): move NativeRollbackExtension to main module
22. refactor(tests): use old() for before/after comparisons in then blocks
23. feat(aggregate): add aggregate(pipeline, Class) overloads with driver-native result mapping
24. fix(native-tx): capture version before encodeUpdate in executeUpdate
25. fix(native-tx): clear GORM session cache on transaction abort
26. feat(core): add withNewNativeTransaction support to MongoEntity
27. feat(examples): add Referral and AuditEvent domains with ReferralService
28. test(examples): fix integration tests for native transaction verification and cleanup
29. feat(examples): add service interaction patterns for native transaction testing
30. refactor(logging): improve log levels and guard hot-path evaluation
31. fix(MongoDatastoreTransactionManager): use getDatastore() accessor
32. feat: KOZA-524: create examples integration tests for multi collections + transaction handling
33. feat(KOZA-527i): [ci] add CodeArtifact publishing using GitHub secrets
34. *(plus the debug logging commits we added earlier)*

## Files Modified

Key files with native transaction implementation:
- `MongoDatastore.java` - Session creation logic
- `MongoDatastoreTransactionManager.groovy` - Transaction management
- `MongoNativeCodecSession.groovy` - NEW: Native session implementation
- `MongoNativeTransactionContext.groovy` - NEW: ThreadLocal context
- `MongoEntity.groovy` - NEW: withNewNativeTransaction DSL
- Plus test files and example project

Build configuration:
- `build.gradle` - CodeArtifact publishing configuration
- `.github/workflows/gradle.yml` - CI workflow with CodeArtifact publishing

## Conflict Resolution

**File**: `.github/workflows/gradle.yml`

**Conflict**: The 8.1.x base had original Grails Artifactory publishing, while our commits added CodeArtifact publishing.

**Resolution**: Kept the CodeArtifact publishing configuration from our commits (removed the Grails Artifactory publishing steps).

## Verification

✅ All 34 commits applied successfully
✅ Versions remain compatible (datastore 8.0.4)
✅ Build configuration updated for CodeArtifact
✅ CI workflow configured
✅ No unresolved conflicts

## Next Steps

1. **Push branches to origin**:
   ```bash
   git push origin feat/native-mongodb-transactions-8.1
   git push origin feat/native-mongodb-transactions-8.1_initial_solution
   ```

2. **Create PR**: `feat/native-mongodb-transactions-8.1_initial_solution` → `feat/native-mongodb-transactions-8.1`

3. **Review checklist**:
   - Verify all native transaction functionality is present
   - Check that versions are correct (8.0.4 datastore)
   - Review conflict resolution in gradle.yml
   - Ensure CI configuration is correct

4. **After PR approval**:
   - Test build locally: `./gradlew clean build publishToMavenLocal`
   - Update core-platform to use 8.1.3-cohere.1-SNAPSHOT
   - Run core-platform integration tests
   - Publish to CodeArtifact

## Branch Strategy

```
upstream/8.1.x (grails/grails-data-mongodb)
    ↓
feat/native-mongodb-transactions-8.1 (clean reference)
    ↓
feat/native-mongodb-transactions-8.1_initial_solution (with native tx commits)
```

This strategy allows:
- Clean reference to upstream 8.1.x
- Clear diff showing all our changes
- Easy rebasing if upstream updates
- Clear PR for review

---

**Created**: 2026-05-09
**Repository**: gorm-mongodb (CohereHealth fork)
**Base**: grails-data-mongodb 8.1.x @ upstream
