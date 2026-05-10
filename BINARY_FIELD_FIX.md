# Fix for Binary Field Validation Issue (KOZA-527)

## Problem

When saving a `ServiceRequestRule` (which extends `Rule`) with a `Binary conditionBlob` field, the validation fails with:

```
java.lang.IllegalArgumentException: Property [conditionBlob] is not a valid property of class com.coherehealth.rule.ServiceRequestRule
    at grails.gorm.validation.PersistentEntityValidator.validatePropertyWithConstraint
```

## Root Cause

The issue occurs in `MongoMappingContext.MongoDocumentMappingFactory.isCustomType()` method.

The MongoDB driver provides a codec for `org.bson.types.Binary`, which is registered in the default codec registry. When GORM's MappingFactory checks if a property type is "custom", it calls `isCustomType()`, which returns `true` for Binary because:

```java
public boolean isCustomType(Class<?> propertyType) {
    return super.isCustomType(propertyType) || hasCodecForType(propertyType);
}
```

Since Binary has a codec, `hasCodecForType(Binary.class)` returns `true`, making `isCustomType()` return `true`.

However, Binary is also a MongoDB native type (listed in `MONGO_NATIVE_TYPES`), and should be treated as a **simple type**, not a custom type.

The problem is the order of type checking in GORM's property creation:
1. Is it an identity? ❌
2. Is it an association? ❌
3. Is it a custom type? ✅ **Binary gets caught here**
4. Is it a simple type? ❌ **Binary never reaches here**

When Binary is incorrectly classified as a custom type, the validation framework cannot find it in the persistent entity's property list, causing the validation error.

## Solution

The fix ensures that MongoDB native types are NOT treated as custom types, even if they have codecs. They should always be handled as simple types.

### Changed File

`grails-datastore-gorm-mongodb/src/main/groovy/org/grails/datastore/mapping/mongo/config/MongoMappingContext.java`

```java
@Override
public boolean isCustomType(Class<?> propertyType) {
    // MongoDB native types should NOT be treated as custom types, even if they have codecs
    // They should be handled as simple types to ensure proper property registration
    if (isMongoNativeType(propertyType)) {
        return false;
    }
    return super.isCustomType(propertyType) || hasCodecForType(propertyType);
}
```

### Why This Fix Works

1. **Priority**: By checking `isMongoNativeType()` first, we ensure MongoDB native types (including Binary) are never misclassified as custom types.

2. **Consistency**: The fix aligns with how `isSimpleType()` already works, which correctly identifies MongoDB native types as simple types.

3. **Property Registration**: When Binary is correctly classified as a simple type, GORM's MappingFactory creates a proper `Simple` persistent property for it, which the validation framework can recognize.

## Testing

### New Test

Created `BinaryFieldValidationSpec.groovy` that tests:
- Binary fields in parent classes
- Binary fields in child classes (inheritance scenario)
- Nullable Binary field constraints
- Validation with Binary fields

All tests pass successfully.

### Existing Tests

The existing `MongoTypesSpec` test continues to pass, confirming backward compatibility.

## Impact

This fix affects only how MongoDB native types are classified during property creation. Types affected include:
- `org.bson.types.Binary`
- `org.bson.types.ObjectId`
- `org.bson.Document`
- Other types in `MONGO_NATIVE_TYPES` set

The change ensures these types are always treated as simple types, regardless of codec availability.

## Related Issues

- KOZA-527: ServiceRequestRule.save() failing with Binary field validation error
