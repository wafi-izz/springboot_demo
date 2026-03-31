# Phase 1 — Harden the Monolith

## 1.1 Database Migrations with Flyway

### What's wrong right now

Your app uses `spring.jpa.hibernate.ddl-auto=update`. Here's what that actually does:

1. On startup, Hibernate compares your `@Entity` classes to the actual database schema
2. It generates `ALTER TABLE` statements to make the DB match
3. It runs them — silently, with no record, no rollback, no review

**Problems in production:**
- It **never drops** columns or tables. Rename a field? You get a new column, the old one stays forever.
- It can't do data migrations (e.g., "split `name` into `firstName` + `lastName` and populate them")
- There's no history — you can't answer "what changed in the schema last Tuesday?"
- Two developers with different entity states can cause conflicting schema changes
- It can make destructive changes on some databases (e.g., changing a column type can lose data)

### What Flyway does instead

Flyway treats your database schema as **versioned code**. You write SQL migration files, numbered in order:

```
src/main/resources/db/migration/
├── V1__create_users_table.sql
├── V2__add_indexes.sql
├── V3__add_audit_columns.sql
```

On startup, Flyway:
1. Checks a `flyway_schema_history` table — "which migrations have already run?"
2. Finds new migration files that haven't been applied
3. Runs them in version order
4. Records each migration in `flyway_schema_history` (version, checksum, execution time)

If you tamper with an already-applied migration file (change its contents), Flyway **refuses to start** — checksum mismatch. This guarantees every environment (dev, staging, prod) has the exact same schema history.

### Naming convention

```
V1__create_users_table.sql
│││  │
││└──└── Description (underscores for spaces)
│└───── Two underscores (required separator)
└────── Version number (integers, or dotted: V1.1, V1.2)
```

- **V** = versioned migration (runs once, in order)
- **R** = repeatable migration (runs every time its checksum changes — used for views, stored procedures)

### How Spring Boot auto-configures Flyway

When Spring Boot finds `flyway-core` on the classpath:

1. `FlywayAutoConfiguration` kicks in (you can see this with `--debug`)
2. It creates a `Flyway` bean using your `spring.datasource.*` properties
3. It runs `Flyway.migrate()` **before** JPA/Hibernate initializes
4. Only then does Hibernate validate the schema (if `ddl-auto=validate`)

This ordering is critical — Flyway sets up the schema, then Hibernate confirms it matches your entities.

### The `flyway_schema_history` table

After the first migration, Flyway creates this table:

| installed_rank | version | description | type | script | checksum | installed_by | installed_on | execution_time | success |
|---|---|---|---|---|---|---|---|---|---|
| 1 | 1 | create users table | SQL | V1__create_users_table.sql | -123456789 | postgres | 2026-03-20 | 42 | true |

Key fields:
- **checksum**: hash of the file contents. If you edit an applied migration, the hash changes and Flyway blocks startup.
- **success**: if a migration fails halfway, this is `false` and you must manually fix the DB + mark it resolved.

### What you need to write

Your current schema (from `User` entity + `ddl-auto=update`) looks like this in PostgreSQL:

```sql
-- Schema: user_management
-- Table: users
-- Columns: id (bigserial PK), first_name, last_name, email, username, password, role (varchar)
```

Your first migration (`V1`) must **exactly match** this existing schema — you're not creating it from scratch (it already exists), you're establishing Flyway's baseline. There are two approaches:

**Option A: Baseline on existing DB** — Tell Flyway the DB is already at V1:
```properties
spring.flyway.baseline-on-migrate=true
spring.flyway.baseline-version=1
```
Then V1 is skipped (marked as baseline), and V2+ run normally.

**Option B: Fresh start** — Drop and recreate the schema, let V1 create it cleanly. Fine for a learning project.

### Configuration properties

```properties
# Disable Hibernate schema management — Flyway owns the schema now
spring.jpa.hibernate.ddl-auto=validate

# Flyway config
spring.flyway.enabled=true
spring.flyway.schemas=user_management
spring.flyway.default-schema=user_management
```

`ddl-auto=validate` means Hibernate will **check** that your entities match the DB schema on startup, but won't change anything. If there's a mismatch, the app refuses to start — which is exactly what you want.

### Common mistakes

1. **Editing an applied migration** — Flyway checksums catch this. You must write a *new* migration instead.
2. **Forgetting the double underscore** — `V1_description.sql` won't be recognized. It must be `V1__description.sql`.
3. **Non-idempotent SQL** — If a migration fails halfway (e.g., creates table A but fails on table B), the DB is in a partial state. Use transactions or write idempotent SQL (`CREATE TABLE IF NOT EXISTS`).
4. **Using `ddl-auto=update` alongside Flyway** — They fight. Flyway creates the schema, then Hibernate tries to "fix" it. Pick one. Flyway + `validate` is the answer.

### 1.2 DTO Mapping with MapStruct

### What's wrong right now

Look at your `UserController`. Every create/update endpoint has inline mapping logic like this:

```java
User user = new User();
user.setFirstName(createUser.getFirstName());
user.setLastName(createUser.getLastName());
user.setEmail(createUser.getEmail());
// ... repeat for every field
```

And your endpoints return `User` entities directly. You have `@JsonProperty(access = WRITE_ONLY)` on `password` — but that's a fragile band-aid. Add a new sensitive field and forget the annotation? It's leaked. Your API response shape is now permanently coupled to your JPA entity shape — any schema change is a breaking API change.

**Three problems:**
1. **Boilerplate** — N fields means N lines of mapping, in every method, for every DTO direction
2. **Error-prone** — miss a field, misspell a setter, forget to update when you add a column — all silent bugs
3. **Entity leakage** — returning `User` directly means your API contract is your database schema

### What MapStruct does

MapStruct is a **compile-time code generator**. You write a Java interface declaring what you want to map. At compile time, MapStruct's annotation processor generates the implementation class — plain Java setter calls, zero reflection, zero runtime overhead.

```java
@Mapper(componentModel = "spring")
public interface UserMapper {
    UserResponse toResponse(User user);
}
```

At compile time, this generates something like:

```java
@Component
public class UserMapperImpl implements UserMapper {
    @Override
    public UserResponse toResponse(User user) {
        if (user == null) return null;
        UserResponse response = new UserResponse();
        response.setId(user.getId());
        response.setFirstName(user.getFirstName());
        // ... every matching field
        return response;
    }
}
```

That generated class is a Spring `@Component` — you `@Autowired` it like any other bean.

### How it works under the hood

**Annotation processing** runs during `javac` compilation, before your code is bytecode. Here's the pipeline:

1. `javac` starts compiling your code
2. It discovers annotation processors on the classpath (MapStruct's `MappingProcessor`)
3. For every interface annotated with `@Mapper`, the processor:
   - Reads the source and target types
   - Matches fields by name and type
   - Generates a `.java` implementation file into `target/generated-sources/annotations/`
4. `javac` compiles the generated source alongside your code
5. Spring's component scan finds the `@Component`-annotated generated class

**Key insight:** This is the same mechanism Lombok uses, but MapStruct generates *visible, debuggable source files*. You can open `target/generated-sources/annotations/.../UserMapperImpl.java` and read exactly what it does. Do this — it demystifies the tool completely.

**Why `componentModel = "spring"` matters:** Without it, MapStruct generates a plain class with no Spring annotations. You'd have to instantiate it manually with `Mappers.getMapper(UserMapper.class)`. With `"spring"`, the generated impl gets `@Component`, Spring picks it up during component scanning, and you inject it via constructor injection like any other dependency.

### Setup — pom.xml changes

You need two things: the MapStruct library and its annotation processor.

```xml
<properties>
    <mapstruct.version>1.6.3</mapstruct.version>
</properties>

<dependencies>
    <!-- MapStruct core -->
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.mapstruct</groupId>
                        <artifactId>mapstruct-processor</artifactId>
                        <version>${mapstruct.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Why the processor goes in `annotationProcessorPaths` and not `dependencies`:** The processor is only needed at compile time — it generates code, then it's done. Putting it in `annotationProcessorPaths` scopes it to the compiler plugin only, keeping your runtime classpath clean. If you put it in `<dependencies>`, it works but ships unnecessary classes in your artifact.

### Step 1: Create `UserResponse` DTO

Before you write the mapper, you need a response DTO. This is the contract between your API and your clients. It should contain **only what the client should see** — no password, no internal JPA state.

Create `src/main/java/com/example/demo/dto/user/UserResponse.java`:

```java
public class UserResponse {
    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String username;
    private Role role;

    // getters and setters
}
```

No `password`. No `authorities`. No `accountNonLocked`. This is your API contract — it's deliberately separate from your entity.

### Step 2: Create `UserMapper`

Create `src/main/java/com/example/demo/mapper/UserMapper.java`:

```java
@Mapper(componentModel = "spring")
public interface UserMapper {

    // Entity -> Response (for all read endpoints)
    UserResponse toResponse(User user);

    // CreateUser DTO -> Entity (for create endpoints)
    User toEntity(CreateUser dto);

    // Update an existing entity from an UpdateUser DTO (for PUT)
    @MappingTarget
    void updateEntity(UpdateUser dto, @MappingTarget User user);
}
```

**How field matching works:** MapStruct matches by field name. `CreateUser.firstName` maps to `User.firstName` because the names match. If names differ, you use `@Mapping(source = "x", target = "y")`. You don't need any `@Mapping` annotations here because your DTO and entity field names already align.

### Step 3: Understand `@MappingTarget`

`@MappingTarget` is critical for update operations. Without it, MapStruct creates a **new** entity. With it, MapStruct updates an **existing** one:

```java
void updateEntity(UpdateUser dto, @MappingTarget User user);
```

Generated code looks like:

```java
@Override
public void updateEntity(UpdateUser dto, User user) {
    if (dto == null) return;
    user.setFirstName(dto.getFirstName());
    user.setLastName(dto.getLastName());
    user.setEmail(dto.getEmail());
    user.setUsername(dto.getUsername());
    user.setRole(dto.getRole());
    // password is NOT set — it's not in UpdateUser
}
```

This is exactly what you want for PUT — overwrite all updatable fields on the managed JPA entity. Since `UpdateUser` doesn't have a `password` field, the password is untouched.

### Step 4: PATCH support with `NullValuePropertyMappingStrategy`

Your controller has PATCH endpoints. PATCH means "update only the fields I send." If a field is `null` in the request, leave the existing value alone.

The problem: by default, MapStruct maps `null` fields too — it would overwrite existing values with `null`.

Solution:

```java
@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
void patchEntity(UpdateUser dto, @MappingTarget User user);
```

Generated code becomes:

```java
@Override
public void patchEntity(UpdateUser dto, User user) {
    if (dto == null) return;
    if (dto.getFirstName() != null) {
        user.setFirstName(dto.getFirstName());
    }
    if (dto.getLastName() != null) {
        user.setLastName(dto.getLastName());
    }
    // ... null checks on every field
}
```

**Important:** For this to work, your `UpdateUser` DTO fields must be nullable — no primitive types. If `role` were `int` instead of `Role`, it couldn't be null, and MapStruct couldn't distinguish "not sent" from "sent as 0". Your DTOs already use object types, so you're fine. But you'll need to **remove the `@NotBlank`/`@NotNull` validation annotations from `UpdateUser`** for PATCH to make sense — you can't require all fields AND allow partial updates. One approach: create a separate `PatchUser` DTO without validation, or apply validation groups.

### Step 5: Wire it into the controller

Inject the mapper and replace inline mapping:

```java
private final UserMapper userMapper;

// Constructor injection — Spring autowires the generated impl

// CREATE — before:
User user = new User();
user.setFirstName(createUser.getFirstName());
// ... 6 lines of mapping
return userRepository.save(user);

// CREATE — after:
User user = userMapper.toEntity(createUser);
user.setPassword(passwordEncoder.encode(createUser.getPassword()));
return ResponseEntity.ok(userMapper.toResponse(userRepository.save(user)));

// UPDATE — before:
existingUser.setFirstName(dto.getFirstName());
// ... more manual mapping
return userRepository.save(existingUser);

// UPDATE — after:
userMapper.updateEntity(dto, existingUser);
return ResponseEntity.ok(userMapper.toResponse(userRepository.save(existingUser)));

// GET — before:
return userRepository.findById(id).orElseThrow();  // returns User entity!

// GET — after:
User user = userRepository.findById(id).orElseThrow();
return ResponseEntity.ok(userMapper.toResponse(user));
```

**Note on password:** MapStruct will map `password` from `CreateUser` to `User`, but you need it encoded. Two options:
1. Map it via MapStruct, then overwrite with the encoded version (simplest)
2. Use `@Mapping(target = "password", ignore = true)` on `toEntity` and set it manually

Option 1 is pragmatic. Option 2 is cleaner if you want the mapper to never touch passwords.

### Step 6: List mapping comes free

Once you define `toResponse(User user)`, MapStruct automatically knows how to map lists:

```java
List<UserResponse> toResponseList(List<User> users);
```

The generated code just iterates and calls `toResponse` on each element. You don't need to write the loop yourself.

### Gotchas & things to watch for

**1. Unmapped fields warning:** MapStruct warns at compile time if a target field has no source match. This is intentional — it catches field drift. If you *want* to ignore a field:

```java
@Mapping(target = "password", ignore = true)
User toEntity(CreateUser dto);
```

**2. Compile, don't just run:** MapStruct runs at compile time. If you change a DTO or entity, you need to recompile (`mvn compile`) before the mapping updates. Your IDE might cache the old generated code.

**3. Check the generated code:** After your first compile, open `target/generated-sources/annotations/com/example/demo/mapper/UserMapperImpl.java`. Read it. This is not magic — it's generated Java. Understanding what it generates is the difference between using a tool and understanding a tool.

**4. Debugging:** If a mapping is wrong, don't guess — read the generated source. It's plain Java with plain setters. You can even set breakpoints in it.

**5. `BatchUpdateUser` has an `id` field:** When mapping `BatchUpdateUser` to a `User`, you probably don't want MapStruct to overwrite the entity's `id`. Use `@Mapping(target = "id", ignore = true)` for batch update methods.

### The complete mapper

Here's what your final `UserMapper` should look like:

```java
@Mapper(componentModel = "spring")
public interface UserMapper {

    UserResponse toResponse(User user);

    List<UserResponse> toResponseList(List<User> users);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "password", ignore = true)
    User toEntity(CreateUser dto);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "password", ignore = true)
    void updateEntity(UpdateUser dto, @MappingTarget User user);

    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "password", ignore = true)
    void patchEntity(UpdateUser dto, @MappingTarget User user);
}
```

### Checklist — what to do

1. Add MapStruct dependency + annotation processor to `pom.xml`
2. Create `UserResponse` DTO
3. Create `UserMapper` interface in `com.example.demo.mapper`
4. Run `mvn compile` and inspect the generated `UserMapperImpl.java`
5. Inject `UserMapper` into `UserController` and `UserService`
6. Replace all manual mapping with mapper calls
7. Replace all `User` returns with `UserResponse` returns
8. Test every endpoint — make sure responses no longer contain `password`, `authorities`, etc.

### 1.3 JPA Specifications for Dynamic Queries

#### What's Wrong With the Current Approach

Right now your repository has methods like `findByEmail`, `findByUsername`. These are **static queries** — one method per query shape. What happens when someone wants:

- Users where role = ADMIN
- Users where role = ADMIN AND name contains "john"
- Users where email like "%@company.com" AND created after last week
- Any arbitrary combination of the above

You'd need a `findByRoleAndFirstNameContainingAndEmailContainingAndCreatedAtAfter(...)` — and a separate method for every combination. This is the **combinatorial explosion problem**. With 4 filter fields, you'd need 2⁴ = 16 methods to cover all combos.

**JPA Specifications** solve this by letting you compose query predicates at runtime. Each spec is a reusable building block.

---

#### How It Works Under the Hood

**The Criteria API** is JPA's programmatic query-building API. Instead of writing JPQL strings, you build query trees:

```java
// This is what JPA Specifications abstract over:
CriteriaBuilder cb = entityManager.getCriteriaBuilder();
CriteriaQuery<User> query = cb.createQuery(User.class);
Root<User> root = query.from(User.class);

// WHERE role = 'ADMIN' AND firstName LIKE '%john%'
Predicate rolePred = cb.equal(root.get("role"), Role.ADMIN);
Predicate namePred = cb.like(cb.lower(root.get("firstName")), "%john%");
query.where(cb.and(rolePred, namePred));
```

This is verbose. **`Specification<T>`** is Spring Data's wrapper:

```java
@FunctionalInterface
public interface Specification<T> {
    Predicate toPredicate(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder cb);
}
```

It's a functional interface — takes the Criteria API pieces, returns a `Predicate`. The magic is that `Specification` has `.and()`, `.or()`, and `.not()` default methods, so you can compose them:

```java
Specification<User> spec = hasRole(Role.ADMIN).and(nameLike("john"));
```

**`JpaSpecificationExecutor<T>`** is the interface that gives your repository the ability to run specs:

```java
public interface JpaSpecificationExecutor<T> {
    Optional<T> findOne(Specification<T> spec);
    List<T> findAll(Specification<T> spec);
    Page<T> findAll(Specification<T> spec, Pageable pageable);  // ← we'll use this in 1.4
    List<T> findAll(Specification<T> spec, Sort sort);
    long count(Specification<T> spec);
    boolean exists(Specification<T> spec);
}
```

When you call `repository.findAll(spec)`, Spring Data:
1. Creates a `CriteriaQuery`
2. Calls your `spec.toPredicate(root, query, cb)` to get the WHERE clause
3. Executes the query via the EntityManager

---

#### The `Root<T>`, `CriteriaBuilder`, and `CriteriaQuery` Triangle

These are the three objects you get in every Specification:

- **`Root<T> root`** — represents the entity (`User`) in the FROM clause. You call `root.get("fieldName")` to reference columns. This returns a `Path<>` which is type-safe at the JPA metamodel level.

- **`CriteriaBuilder cb`** — factory for predicates. `cb.equal()`, `cb.like()`, `cb.greaterThan()`, `cb.and()`, `cb.or()`, etc. Think of it as your SQL operator toolkit.

- **`CriteriaQuery<?> query`** — the query being built. You rarely touch this in specs (it's more for subqueries or distinct). The `?` wildcard is there because the same spec can be used in count queries.

---

#### String Field Safety: `root.get("fieldName")`

Using `root.get("role")` with a string is convenient but fragile — rename the field and it breaks at runtime with a cryptic Hibernate error. There are two ways to make this safer:

1. **JPA Metamodel** (generated `User_` class) — `root.get(User_.role)`. Compile-time safety. Requires the `hibernate-jpamodelgen` annotation processor. We'll skip this for now but know it exists.

2. **Constants** — define field names as `static final String` in your spec class. Not compile-safe but at least one place to fix.

For this phase, string literals are fine. You'll feel the pain if you rename a field — that's the lesson.

---

#### Note on `createdAfter` Spec

The plan mentions a `createdAfter(LocalDateTime)` specification. Your `User` entity doesn't have a `createdAt` field yet — that's coming in **Phase 1.5 (JPA Auditing)**. For now, we'll build the first three specs (`hasRole`, `nameLike`, `emailLike`) and wire up the endpoint. When you add auditing fields in 1.5, you'll come back and add `createdAfter` — which is a good exercise in extending specs.

---

#### Implementation Steps

**Step 1: Extend UserRepository**

Your repository needs to extend `JpaSpecificationExecutor<User>` in addition to `JpaRepository`:

```java
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    // existing methods stay
}
```

This adds `findAll(Specification<User>)`, `count(Specification<User>)`, etc.

**Step 2: Create `UserSpecifications` class**

Create `src/main/java/com/example/demo/specification/UserSpecifications.java`:

```java
package com.example.demo.specification;

import com.example.demo.entity.Role;
import com.example.demo.entity.User;
import org.springframework.data.jpa.domain.Specification;

public class UserSpecifications {

    public static Specification<User> hasRole(Role role) {
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    public static Specification<User> firstNameLike(String name) {
        return (root, query, cb) -> cb.like(
            cb.lower(root.get("firstName")),
            "%" + name.toLowerCase() + "%"
        );
    }

    public static Specification<User> emailLike(String email) {
        return (root, query, cb) -> cb.like(
            cb.lower(root.get("email")),
            "%" + email.toLowerCase() + "%"
        );
    }
}
```

Key points:
- **Static methods** returning `Specification<User>` — these are your composable building blocks
- **This is NOT a Spring `@Component`** — it's a utility class. Specifications are stateless functions, no need for DI.
- **Case-insensitive** — `cb.lower()` + `.toLowerCase()` on the input
- **Wildcard wrapping** — `%` on both sides for LIKE. The caller passes just the search term.

**Step 3: Add filter method to UserService**

Add to `UserService` interface:

```java
List<User> filter(Role role, String name, String email);
```

Implement in `UserServiceImpl`:

```java
import com.example.demo.specification.UserSpecifications;
import org.springframework.data.jpa.domain.Specification;

// In the filter method:
public List<User> filter(Role role, String name, String email) {
    Specification<User> spec = Specification.where(null); // start with no filter (matches all)

    if (role != null) {
        spec = spec.and(UserSpecifications.hasRole(role));
    }
    if (name != null && !name.isBlank()) {
        spec = spec.and(UserSpecifications.firstNameLike(name));
    }
    if (email != null && !email.isBlank()) {
        spec = spec.and(UserSpecifications.emailLike(email));
    }

    return userRepository.findAll(spec);
}
```

The **`Specification.where(null)`** trick is important: a `null` spec means "no restriction" — `WHERE true`. This gives you a clean starting point to `.and()` onto conditionally. Without this, you'd need ugly null-checking on the first spec.

**Step 4: Add filter endpoint to UserController**

```java
@GetMapping("/users/filter")
@Operation(summary = "filter users", description = "filter users by optional criteria")
@ApiResponses({
    @ApiResponse(responseCode = "200", description = "Success",
        content = @Content(array = @ArraySchema(schema = @Schema(implementation = UserResponse.class)))),
    @ApiResponse(responseCode = "503", description = "Service unavailable",
        content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
})
public ResponseEntity<List<UserResponse>> filterUsers(
        @RequestParam(required = false) Role role,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) String email) {
    List<User> users = userService.filter(role, name, email);
    return ResponseEntity.ok(userMapper.toResponseList(users));
}
```

Key points:
- **`@RequestParam(required = false)`** — all params are optional. No params = return all users.
- **Spring auto-converts `Role`** — passing `?role=ADMIN` auto-converts the string to the `Role.ADMIN` enum. Spring's `StringToEnumConverterFactory` handles this. If someone passes `?role=INVALID`, Spring returns 400 automatically.
- **Place this BEFORE `/users/{id}`** in your controller — otherwise Spring might try to interpret `filter` as an `{id}` path variable. Actually, since `/users/filter` is a different path pattern than `/users/{id}`, Spring resolves it correctly — literal segments take priority over path variables. But it's still good practice to order specific routes first.

**Step 5: Test the endpoint**

```
GET /api/users/filter                          → all users
GET /api/users/filter?role=ADMIN               → admins only
GET /api/users/filter?name=john                → firstName contains "john"
GET /api/users/filter?role=ADMIN&name=john     → admin + name match
GET /api/users/filter?email=company.com        → email contains "company.com"
GET /api/users/filter?role=INVALID             → 400 Bad Request (auto)
```

**Step 6: Compose in tests (understand the composition)**

This is where specs shine. In your service or anywhere else, you can do:

```java
// OR composition
Specification<User> adminsOrManagers = UserSpecifications.hasRole(Role.ADMIN)
    .or(UserSpecifications.hasRole(Role.MANAGER));

// Negate
Specification<User> notAdmin = Specification.not(UserSpecifications.hasRole(Role.ADMIN));

// Complex
Specification<User> complex = UserSpecifications.hasRole(Role.ADMIN)
    .and(UserSpecifications.firstNameLike("john").or(UserSpecifications.emailLike("john")));
// WHERE role = 'ADMIN' AND (firstName LIKE '%john%' OR email LIKE '%john%')
```

---

#### What SQL Gets Generated

When you pass `hasRole(ADMIN).and(nameLike("john"))` to `findAll()`, Hibernate generates:

```sql
SELECT u.* FROM user_management.users u
WHERE u.role = 'ADMIN' AND LOWER(u.first_name) LIKE '%john%'
```

Enable SQL logging to see this:
```properties
spring.jpa.show-sql=true
spring.jpa.properties.hibernate.format_sql=true
```

(Turn this off before production — it's noisy.)

---

#### Common Gotchas

1. **`root.get("fieldName")` uses the Java field name, not the DB column name.** So `root.get("firstName")`, not `root.get("first_name")`. JPA translates through your `@Column` mapping.

2. **`Specification.where(null)` vs `Specification.where(someSpec)`** — `where(null)` is valid and means "no restriction." This is intentional API design. But `.and(null)` on an existing spec throws NPE in some Spring Data versions — always guard with `if` checks.

3. **N+1 queries with specs** — if your entity has lazy relationships, specs can trigger N+1 just like any other query. Not an issue for `User` right now, but know that `root.fetch("relationship")` exists for specs too.

4. **Case sensitivity** — PostgreSQL's `LIKE` is case-sensitive by default. That's why we use `cb.lower()`. Alternatively, PostgreSQL has `ILIKE`, but the Criteria API doesn't have a direct equivalent — `cb.lower()` + `LIKE` is the portable approach.

### 1.4 Pagination & Sorting
<!-- content to be added -->

### 1.5 JPA Auditing
<!-- content to be added -->

### 1.6 Custom AOP — Build Your Own Annotations
<!-- content to be added -->

### 1.7 Bean Validation Deep-Dive
<!-- content to be added -->
