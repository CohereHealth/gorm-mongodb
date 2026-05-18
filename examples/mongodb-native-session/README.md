# MongoDB Native Session Example

This example demonstrates MongoDB native transaction support in GORM, showcasing the behavior of `withNativeTransaction` and immediate execution patterns.

## Features Demonstrated

### Domain Class
- **Person**: Simple domain with optimistic locking support
- **Constraints**: Basic validation rules
- **Mapping**: Custom collection name

### Service Methods
- **createPersonWithNativeTransaction**: Creates a person within native transaction
- **updatePersonWithNativeTransaction**: Updates person with immediate execution
- **deletePersonWithNativeTransaction**: Deletes person within transaction scope
- **createMultiplePersonsWithRollback**: Tests rollback behavior
- **testNestedNativeTransactions**: Demonstrates nested transaction handling

### Integration Tests
- **Basic CRUD Operations**: Create, read, update, delete with native transactions
- **Transaction Rollback**: Verifies proper rollback behavior
- **Nested Transactions**: Tests nested native transaction scenarios
- **Context Detection**: Verifies native transaction context detection
- **Immediate Execution**: Tests immediate operation execution
- **Optimistic Locking**: Verifies version handling in native transactions
- **Transaction Isolation**: Tests proper isolation and rollback

## Key Behaviors Tested

### Immediate Execution
```groovy
Person.withNativeTransaction { session ->
    def person = new Person(firstName: "Test", lastName: "User", age: 30)
    person.save() // Executes immediately, no flush needed
    
    // Person is immediately available in database
    assert Person.findByFirstName("Test") != null
}
```

### Optimistic Locking
```groovy
Person.withNativeTransaction { session ->
    person.age = 31
    person.save() // Version incremented immediately
    assert person.version == originalVersion + 1
}
```

### Rollback Behavior
```groovy
Person.withNativeTransaction { session ->
    new Person(...).save()
    throw new RuntimeException("Force rollback")
    // All changes are rolled back
}
```

## Running the Tests

```bash
./gradlew :examples:mongodb-native-session:integrationTest
```

## Prerequisites

- MongoDB 4.0+ (for native transaction support)
- MongoDB running as replica set or sharded cluster
- Native transactions enabled in configuration

## Configuration

The example enables native transactions in `application.yml`:

```yaml
grails:
    mongodb:
        nativeTransactions: true
```

This configuration ensures that `withNativeTransaction` uses MongoDB's native transaction capabilities instead of falling back to Spring transaction management.