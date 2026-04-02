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

#### What's Wrong Right Now

Your `User` entity has no record of when it was created, when it was last modified, or who did it. In production, this is a blind spot:

- Support gets a ticket: "my account was changed" — you can't tell when or by whom
- You need to debug a data issue — no timestamps to correlate with logs
- Compliance/audit requirements demand a trail of who modified what

You could manually set `createdAt = LocalDateTime.now()` in every create method, but that's:
1. Repetitive across every entity you'll ever create
2. Easy to forget
3. Spread across service methods instead of centralized

#### How Spring Data JPA Auditing Works

Spring Data JPA provides **automatic auditing** via JPA lifecycle callbacks. Here's the full mechanism:

**1. `@EntityListeners(AuditingEntityListener.class)`**

This tells JPA: "Before persisting or updating this entity, call `AuditingEntityListener`'s callback methods."

Under the hood, JPA defines lifecycle callback annotations:
- `@PrePersist` — called before `INSERT`
- `@PreUpdate` — called before `UPDATE`
- `@PostPersist`, `@PostUpdate`, `@PostLoad`, `@PreRemove`, `@PostRemove` — others

`AuditingEntityListener` is Spring's implementation that hooks into `@PrePersist` and `@PreUpdate`. When triggered, it:
1. Looks for fields annotated with `@CreatedDate`, `@LastModifiedDate`, `@CreatedBy`, `@LastModifiedBy`
2. For date fields: calls `LocalDateTime.now()` (or the configured `DateTimeProvider`)
3. For "by" fields: calls your `AuditorAware` bean to get the current user
4. Sets the values on the entity

**2. `@EnableJpaAuditing`**

This annotation on a `@Configuration` class registers the Spring infrastructure that `AuditingEntityListener` depends on:
- Registers an `AuditingHandler` bean
- Looks for an `AuditorAware` bean (for `@CreatedBy`/`@LastModifiedBy`)
- Optionally accepts a `DateTimeProvider` bean (defaults to system clock)

Without `@EnableJpaAuditing`, the annotations on your fields are inert — the listener has nothing to delegate to.

**3. `AuditorAware<T>`**

A functional interface you implement:

```java
@FunctionalInterface
public interface AuditorAware<T> {
    Optional<T> getCurrentAuditor();
}
```

This is where you answer: "Who is the current user?" In your case, you pull it from Spring Security's `SecurityContextHolder`. The type parameter `T` matches the type of your `createdBy`/`updatedBy` fields — typically `String` (username) or `Long` (user ID).

#### The `@MappedSuperclass` Pattern

You want audit fields on every entity, not just `User`. The standard approach is a `BaseEntity` (or `AuditableEntity`) superclass:

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(updatable = false)
    private String createdBy;

    @LastModifiedBy
    private String updatedBy;

    // getters and setters
}
```

Key annotations:
- **`@MappedSuperclass`** — tells JPA: "This class isn't an entity itself, but its fields should be inherited by entity subclasses." The columns end up in the child entity's table, not a separate table.
- **`updatable = false`** on `createdAt` and `createdBy` — these should only be set on INSERT, never changed. This is a database-level guard on top of the `@CreatedDate`/`@CreatedBy` semantics.
- **`@LastModifiedDate`** updates on every save, including the first one (creation). So `createdAt` and `updatedAt` will be the same on a brand-new entity.

#### `@MappedSuperclass` vs `@Inheritance`

Don't confuse `@MappedSuperclass` with JPA inheritance strategies (`@Inheritance(strategy = JOINED)`, etc.):

| | `@MappedSuperclass` | `@Inheritance` |
|---|---|---|
| Creates a table? | No — fields go into child tables | Yes (or shared via strategies) |
| Polymorphic queries? | No — can't do `findAll()` on the superclass | Yes |
| Use case | Shared fields (audit, soft-delete) | Actual type hierarchies (e.g., `Payment` → `CreditCardPayment`, `BankTransfer`) |

You want `@MappedSuperclass` here. You're sharing columns, not modeling a type hierarchy.

#### Implementation Steps

**Step 1: Create the Flyway migration**

Create `V3__add_audit_columns.sql`:

```sql
ALTER TABLE user_management.users
    ADD COLUMN created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN updated_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    ADD COLUMN created_by  VARCHAR(255),
    ADD COLUMN updated_by  VARCHAR(255);
```

**Why `DEFAULT NOW()`:** Your existing rows have no values for these columns. Without a default, the `NOT NULL` constraint fails on existing data. `DEFAULT NOW()` gives existing rows a reasonable initial value. New rows will get their values from Spring's auditing — the default is just a safety net for the migration.

After the migration runs, you could remove the default in a future migration if you want to enforce that the application always provides the value. But it's harmless to leave.

**Step 2: Create `BaseEntity`**

Create `src/main/java/com/example/demo/entity/BaseEntity.java`:

```java
package com.example.demo.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @CreatedBy
    @Column(updatable = false)
    private String createdBy;

    @LastModifiedBy
    private String updatedBy;

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
```

**Step 3: Make `User` extend `BaseEntity`**

```java
public class User extends BaseEntity implements UserDetails {
    // everything else stays the same — the audit fields are inherited
}
```

That's it. The four audit columns are now part of `User`'s table mapping via `@MappedSuperclass`.

**Step 4: Implement `AuditorAware`**

Create `src/main/java/com/example/demo/config/AuditorAwareImpl.java`:

```java
package com.example.demo.config;

import org.springframework.data.domain.AuditorAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class AuditorAwareImpl implements AuditorAware<String> {

    @Override
    public Optional<String> getCurrentAuditor() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName);
    }
}
```

How this works:
1. Gets the `Authentication` from Spring Security's thread-local `SecurityContextHolder`
2. Checks if it's actually authenticated (not an anonymous token)
3. Returns the username via `getName()`

If there's no authentication (e.g., during startup data loading or a scheduled job), it returns `Optional.empty()` — the `createdBy`/`updatedBy` fields will be `null`. That's correct behavior.

**Step 5: Enable JPA Auditing**

Add `@EnableJpaAuditing` to your main application class or a dedicated config class:

```java
@SpringBootApplication
@EnableJpaAuditing
public class DemoApplication {
    // ...
}
```

Or create a separate config:

```java
@Configuration
@EnableJpaAuditing
public class JpaConfig {
}
```

Either works. A separate config class is cleaner if you end up with many JPA-related configurations.

**Step 6: Update `UserResponse` DTO**

Add the audit fields to your response so clients can see them:

```java
public class UserResponse {
    // existing fields...
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;
    private String updatedBy;
    // getters/setters
}
```

MapStruct will auto-map these because the field names match `BaseEntity`'s fields. No mapper changes needed.

**Step 7: Update `UserMapper`**

You need to tell MapStruct to ignore the audit fields during `toEntity`, `updateEntity`, and `patchEntity` — these are managed by Spring, not by the client:

```java
@Mapping(target = "createdAt", ignore = true)
@Mapping(target = "updatedAt", ignore = true)
@Mapping(target = "createdBy", ignore = true)
@Mapping(target = "updatedBy", ignore = true)
```

Add these to `toEntity`, `updateEntity`, and `patchEntity` methods. Without this, MapStruct will try to map these fields from DTOs (which don't have them) and give compile warnings.

#### How `@PrePersist` / `@PreUpdate` Work Under the Hood

When Hibernate is about to flush an entity to the database:

1. It checks the entity's metadata for `@EntityListeners`
2. It finds `AuditingEntityListener.class`
3. For a new entity → calls `@PrePersist` callback:
   - `AuditingEntityListener.touchForCreate(entity)`
   - Sets `createdDate`, `lastModifiedDate`, `createdBy`, `lastModifiedBy`
4. For a dirty (modified) entity → calls `@PreUpdate` callback:
   - `AuditingEntityListener.touchForUpdate(entity)`
   - Sets `lastModifiedDate`, `lastModifiedBy` only
5. Then the SQL `INSERT`/`UPDATE` executes

This happens inside the same transaction, before the SQL statement — the audit values are part of the same `INSERT`/`UPDATE`.

#### Gotchas

1. **`@CreatedDate` is also set on `@PreUpdate` if null** — if you somehow have an entity without a `createdAt` (e.g., loaded from legacy data), Spring won't retroactively set it on update. It stays null.

2. **Bulk operations bypass listeners** — `@Modifying @Query("UPDATE User u SET u.role = :role")` goes directly to the database. JPA lifecycle callbacks (including auditing) are NOT triggered. Only operations that go through the EntityManager (`save()`, `persist()`, `merge()`) trigger them.

3. **`createdBy` will be null for unauthenticated operations** — if you have endpoints that create entities without authentication (e.g., user registration), `AuditorAware` returns empty and `createdBy` is null. That's fine — you can use "system" as a fallback:
   ```java
   return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
           .filter(Authentication::isAuthenticated)
           .map(Authentication::getName)
           .or(() -> Optional.of("system"));
   ```

4. **The `createdAfter` spec from 1.3** — now that you have `createdAt`, you can add the `createdAfter(LocalDateTime)` specification to `UserSpecifications`:
   ```java
   public static Specification<User> createdAfter(LocalDateTime dateTime) {
       return (root, query, cb) -> cb.greaterThan(root.get("createdAt"), dateTime);
   }
   ```

#### Checklist

1. Write Flyway migration `V3__add_audit_columns.sql`
2. Create `BaseEntity` with the four audit fields + annotations
3. Make `User` extend `BaseEntity`
4. Create `AuditorAwareImpl` implementing `AuditorAware<String>`
5. Add `@EnableJpaAuditing` to config
6. Update `UserResponse` DTO with audit fields
7. Update `UserMapper` to ignore audit fields on write operations
8. Start the app — verify migration runs and Hibernate validates successfully
9. Create a user via API — verify `createdAt`, `updatedAt` are populated
10. Update a user — verify `updatedAt` changes, `createdAt` stays the same
11. (Optional) Add `createdAfter` spec to `UserSpecifications`

### 1.6 Custom AOP — Build Your Own Annotations

#### What Is AOP and Why Should You Care

AOP (Aspect-Oriented Programming) lets you add behavior to existing code **without modifying it**. You've already been using AOP without knowing it:

- `@Transactional` — wraps your method in a transaction (begin → commit/rollback)
- `@Secured` / `@PreAuthorize` — checks authorization before the method runs
- `@Cacheable` — checks a cache before executing, stores result after
- `@Async` — runs the method in a different thread

All of these work the same way: Spring creates a **proxy** around your bean. When someone calls your method, they're actually calling the proxy, which runs extra logic before/after delegating to the real method.

#### How Spring Proxies Work Under the Hood

When Spring creates a bean that has AOP advice applied, it doesn't give you the actual object. It gives you a **proxy** — a wrapper that intercepts method calls.

**Two proxy strategies:**

1. **JDK Dynamic Proxy** — used when your bean implements an interface. Creates a proxy that implements the same interface. The proxy intercepts calls and delegates to the real object.

2. **CGLIB Proxy** — used when your bean is a concrete class (no interface). Creates a **subclass** of your bean at runtime via bytecode generation. The subclass overrides methods to add the interceptor logic.

Spring Boot defaults to CGLIB (`spring.aop.proxy-target-class=true`). This is why:
- **Private methods can't be intercepted** — CGLIB creates a subclass, and subclasses can't override private methods
- **Self-invocation bypasses the proxy** — if `methodA()` calls `this.methodB()`, the call to `methodB` goes directly to the real object, skipping the proxy entirely. `this` is the real object, not the proxy.

```
Client → Proxy.methodA() → [AOP advice runs] → RealObject.methodA()
                                                       ↓
                                                  this.methodB()  ← NO proxy, AOP skipped!
```

This is the #1 gotcha with Spring AOP. `@Transactional` on `methodB` does nothing if called from `methodA` in the same class.

#### AOP Terminology (The Ones That Matter)

- **Aspect** — a class containing advice. Annotated with `@Aspect` and `@Component`.
- **Advice** — the code that runs. Types:
  - `@Before` — runs before the method
  - `@After` — runs after the method (regardless of outcome)
  - `@AfterReturning` — runs after successful return
  - `@AfterThrowing` — runs after an exception
  - `@Around` — wraps the method entirely. You control when/if the method executes. Most powerful.
- **JoinPoint** — the method being intercepted. Gives you access to method name, arguments, target object.
- **ProceedingJoinPoint** — extends JoinPoint, used in `@Around`. You call `proceed()` to execute the actual method.
- **Pointcut** — the expression that defines *which* methods to intercept. Can match by annotation, package, method name, etc.

#### Dependency

You need `spring-boot-starter-aop`. Check your `pom.xml` — if you already have `spring-boot-starter-web`, AOP is likely transitively included. If not:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

---

## Part 1: `@LogExecutionTime`

This is the simplest custom AOP annotation — logs how long a method takes to execute.

### Step 1: Create the annotation

Create `src/main/java/com/example/demo/annotation/LogExecutionTime.java`:

```java
package com.example.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogExecutionTime {
}
```

Understanding the meta-annotations:
- **`@Target(ElementType.METHOD)`** — this annotation can only go on methods. Other options: `TYPE` (class), `FIELD`, `PARAMETER`, etc.
- **`@Retention(RetentionPolicy.RUNTIME)`** — the annotation is available at runtime via reflection. This is **required** for AOP — Spring needs to see the annotation at runtime to apply advice. `RetentionPolicy.SOURCE` (like `@Override`) is erased at compile time. `CLASS` is in the bytecode but not accessible via reflection.

### Step 2: Create the Aspect

Create `src/main/java/com/example/demo/aspect/LogExecutionTimeAspect.java`:

```java
package com.example.demo.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class LogExecutionTimeAspect {

    private static final Logger log = LoggerFactory.getLogger(LogExecutionTimeAspect.class);

    @Around("@annotation(com.example.demo.annotation.LogExecutionTime)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().toShortString();
        Object[] args = joinPoint.getArgs();

        log.info("→ {} called with args: {}", methodName, args);

        long start = System.currentTimeMillis();
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - start;
            log.info("← {} returned in {}ms", methodName, duration);
            return result;
        } catch (Throwable ex) {
            long duration = System.currentTimeMillis() - start;
            log.error("✗ {} failed in {}ms with: {}", methodName, duration, ex.getMessage());
            throw ex;
        }
    }
}
```

Breaking this down:

- **`@Aspect`** — marks this as an AOP aspect. AspectJ annotation, not Spring's.
- **`@Component`** — makes it a Spring bean. Without this, Spring doesn't see it.
- **`@Around("@annotation(com.example.demo.annotation.LogExecutionTime)")`** — the pointcut expression. Means: "intercept any method annotated with `@LogExecutionTime`". The `@annotation()` pointcut designator matches methods carrying a specific annotation.
- **`ProceedingJoinPoint`** — gives you control. You MUST call `proceed()` to execute the real method. If you forget, the method never runs.
- **`return result`** — you MUST return the result. If the method returns `ResponseEntity<User>`, your aspect needs to pass that through. Forgetting this silently returns `null`.
- **`throws Throwable`** — `proceed()` can throw anything. You must either catch+rethrow or declare it.

### Step 3: Apply it

Put `@LogExecutionTime` on any service method:

```java
@LogExecutionTime
public User get(Long id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("User not found"));
}
```

### Step 4: Test it

Call `GET /api/users/1`. Check your console logs — you should see:

```
→ UserServiceImpl.get(..) called with args: [1]
← UserServiceImpl.get(..) returned in 23ms
```

### Pointcut Expression Alternatives

The `@annotation()` pointcut is just one option:

```java
// All methods in service package
@Around("execution(* com.example.demo.service.*.*(..))")

// All public methods in classes annotated with @Service
@Around("within(@org.springframework.stereotype.Service *)")

// Specific method signature
@Around("execution(* com.example.demo.service.UserService.get(Long))")

// Combine with && || !
@Around("@annotation(LogExecutionTime) && execution(public * *(..))")
```

For your custom annotations, `@annotation()` is the right choice — it's explicit, opt-in per method.

---

## Part 2: `@Auditable`

This is more complex — captures the state change and writes it to a database table.

### Step 1: Create the Flyway migration

Create `V4__create_audit_log_table.sql`:

```sql
CREATE TABLE user_management.audit_log (
    id          BIGSERIAL PRIMARY KEY,
    action      VARCHAR(20)  NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id   VARCHAR(100),
    performed_by VARCHAR(255),
    performed_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    details     TEXT
);
```

`details` stores a JSON string of what changed. `TEXT` is fine — we're not querying inside it.

### Step 2: Create the `AuditLog` entity

Create `src/main/java/com/example/demo/entity/AuditLog.java`:

```java
package com.example.demo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "audit_log", schema = "user_management")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String entityType;

    private String entityId;

    private String performedBy;

    @Column(nullable = false)
    private LocalDateTime performedAt;

    private String details;

    // getters and setters
}
```

### Step 3: Create the repository

Create `src/main/java/com/example/demo/repository/AuditLogRepository.java`:

```java
package com.example.demo.repository;

import com.example.demo.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
```

### Step 4: Create the annotation

Create `src/main/java/com/example/demo/annotation/Auditable.java`:

```java
package com.example.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Auditable {
    String action();       // "CREATE", "UPDATE", "DELETE"
    String entity();       // "User"
}
```

Annotation elements look like methods but act like fields. When you use it: `@Auditable(action = "CREATE", entity = "User")`.

### Step 5: Create the Aspect

Create `src/main/java/com/example/demo/aspect/AuditAspect.java`:

```java
package com.example.demo.aspect;

import com.example.demo.annotation.Auditable;
import com.example.demo.entity.AuditLog;
import com.example.demo.repository.AuditLogRepository;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

@Aspect
@Component
public class AuditAspect {

    private final AuditLogRepository auditLogRepository;

    public AuditAspect(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @AfterReturning(pointcut = "@annotation(auditable)", returning = "result")
    public void audit(JoinPoint joinPoint, Auditable auditable, Object result) {
        String entityId = extractEntityId(result);
        String performedBy = getCurrentUser();

        AuditLog log = new AuditLog();
        log.setAction(auditable.action());
        log.setEntityType(auditable.entity());
        log.setEntityId(entityId);
        log.setPerformedBy(performedBy);
        log.setPerformedAt(LocalDateTime.now());
        log.setDetails("Method: " + joinPoint.getSignature().toShortString());

        auditLogRepository.save(log);
    }

    private String extractEntityId(Object result) {
        if (result == null) return null;
        try {
            var method = result.getClass().getMethod("getId");
            Object id = method.invoke(result);
            return id != null ? id.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String getCurrentUser() {
        return Optional.ofNullable(SecurityContextHolder.getContext().getAuthentication())
                .filter(Authentication::isAuthenticated)
                .map(Authentication::getName)
                .orElse("anonymous");
    }
}
```

Key differences from `@LogExecutionTime`:

- **`@AfterReturning`** instead of `@Around` — we only want to audit *successful* operations. If the method throws, no audit entry.
- **`returning = "result"`** — binds the method's return value to the `result` parameter. This gives us the created/updated entity to extract the ID.
- **`@annotation(auditable)`** with lowercase — this binds the annotation instance to the `auditable` parameter. This is how you access `auditable.action()` and `auditable.entity()`. The parameter name must match.
- **`extractEntityId`** uses reflection to call `getId()` on the result. This is pragmatic — it works for any entity with a `getId()` method. In a more sophisticated system you'd use an interface.

### Step 6: Apply it

On your service methods:

```java
@Auditable(action = "CREATE", entity = "User")
public User create(User user) {
    // ...
}

@Auditable(action = "UPDATE", entity = "User")
public User update(Long id, UpdateUser dto) {
    // ...
}

@Auditable(action = "DELETE", entity = "User")
public void delete(Long id) {
    // ...
}
```

### Step 7: Test it

1. Create a user via API
2. Check the `audit_log` table in pgAdmin — you should see a row with action=CREATE, entity_type=User, entity_id=(the new user's ID), performed_by=(your JWT username)

---

## Part 3: `@RateLimit`

In-memory rate limiting per client. Simple but effective.

### Step 1: Create the annotation

Create `src/main/java/com/example/demo/annotation/RateLimit.java`:

```java
package com.example.demo.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    int maxRequests() default 10;
    int windowSeconds() default 60;
}
```

`default` values mean you can use `@RateLimit` without parameters and get 10 requests per 60 seconds. Or override: `@RateLimit(maxRequests = 5, windowSeconds = 30)`.

### Step 2: Create the exception

Create `src/main/java/com/example/demo/exception/RateLimitExceededException.java`:

```java
package com.example.demo.exception;

public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
```

### Step 3: Handle it in your `GlobalExceptionHandler`

Add a handler that returns 429:

```java
@ExceptionHandler(RateLimitExceededException.class)
public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
        HttpStatus.TOO_MANY_REQUESTS, ex.getMessage()
    );
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(problem);
}
```

### Step 4: Create the Aspect

Create `src/main/java/com/example/demo/aspect/RateLimitAspect.java`:

```java
package com.example.demo.aspect;

import com.example.demo.annotation.RateLimit;
import com.example.demo.exception.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Aspect
@Component
public class RateLimitAspect {

    private final Map<String, Queue<Long>> requestLog = new ConcurrentHashMap<>();

    @Around("@annotation(rateLimit)")
    public Object rateLimit(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        String clientKey = getClientKey(joinPoint);
        long now = System.currentTimeMillis();
        long windowStart = now - (rateLimit.windowSeconds() * 1000L);

        Queue<Long> timestamps = requestLog.computeIfAbsent(clientKey, k -> new ConcurrentLinkedQueue<>());

        // Remove expired timestamps
        while (!timestamps.isEmpty() && timestamps.peek() < windowStart) {
            timestamps.poll();
        }

        if (timestamps.size() >= rateLimit.maxRequests()) {
            throw new RateLimitExceededException(
                "Rate limit exceeded. Max " + rateLimit.maxRequests() +
                " requests per " + rateLimit.windowSeconds() + " seconds."
            );
        }

        timestamps.add(now);
        return joinPoint.proceed();
    }

    private String getClientKey(ProceedingJoinPoint joinPoint) {
        String methodName = joinPoint.getSignature().toShortString();
        String clientIp = "unknown";

        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                clientIp = request.getRemoteAddr();
            }
        } catch (Exception ignored) {}

        return clientIp + ":" + methodName;
    }
}
```

How it works:
- **Sliding window** — stores the timestamp of each request in a queue per client+method
- **`ConcurrentHashMap` + `ConcurrentLinkedQueue`** — thread-safe without synchronization
- **Client key** = IP + method name. Each endpoint has its own rate limit counter per client.
- **On each request**: purge expired timestamps, check count against limit, add current timestamp
- **Limitation**: in-memory only — resets on restart, doesn't work across multiple app instances. Redis-based rate limiting (Phase 3) solves both.

### Step 5: Apply it

On your login endpoint (or any controller method):

```java
@RateLimit(maxRequests = 5, windowSeconds = 60)
@PostMapping("/auth/login")
public ResponseEntity<?> login(@RequestBody LoginRequest request) {
    // ...
}
```

You can also put it on service methods, but controller-level makes more sense for rate limiting since you need the HTTP request for the client IP.

### Step 6: Test it

1. Call the login endpoint 5 times rapidly
2. The 6th call should return `429 Too Many Requests` with the ProblemDetail body
3. Wait 60 seconds, try again — it should work

---

## Understanding the Proxy Limitation

After implementing all three, test this to internalize the proxy limitation:

**Self-invocation test:** In `UserServiceImpl`, if you have:

```java
@LogExecutionTime
public List<User> get() {
    return userRepository.findAll();
}

public User get(Long id) {
    get();  // ← this calls get() directly on `this`, NOT on the proxy
    return userRepository.findById(id).orElseThrow(...);
}
```

Calling `get(Long id)` from the controller → the `@LogExecutionTime` on `get()` **will NOT trigger**. The call to `get()` inside `get(Long id)` bypasses the proxy because it uses `this`.

This is the same reason a `@Transactional` method calling another `@Transactional` method in the same class doesn't start a new transaction.

---

## Checklist

1. Add `spring-boot-starter-aop` if not already present
2. Create `@LogExecutionTime` annotation
3. Create `LogExecutionTimeAspect`
4. Apply to service methods and test
5. Create `V4__create_audit_log_table.sql` migration
6. Create `AuditLog` entity + `AuditLogRepository`
7. Create `@Auditable` annotation
8. Create `AuditAspect`
9. Apply to create/update/delete service methods and test
10. Create `@RateLimit` annotation
11. Create `RateLimitExceededException` + handler
12. Create `RateLimitAspect`
13. Apply to login endpoint and test
14. Test the self-invocation limitation to understand it

### 1.7 Bean Validation Deep-Dive
<!-- content to be added -->
