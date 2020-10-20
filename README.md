# scmdb
Tool to better integrate relational DB with version control systems. 
Executes SQL scripts to keep DB in syncs with current source code version when switching back and force between branches.
Maintains directory of all DB object DDLs and automatically updates it when object modified with new script created by developer.

We assume developers implementing SQL scripts to upgrade DB and additionally rollback script - to move DB to the initial state.
Ideally each upgrade script should have it's rollback counterpart.
Following is name convention for script files:
```
<script oder num>_<short description>.sql
<script oder num>_<short description>_rollback.sql
```
Additionally scripts ending with _user.sql, _rpt.sql, _pkg.sql will be executed in it's own DB schema (see below)

### Requirements
scmdb uses Oracle's command line tool [SQLcl](https://www.oracle.com/database/technologies/appdev/sqlcl.html) to execute scripts and generate DDLs. SQLcl should be installed and accessible in standard path

### Supported command line options:
* ```--owner-schema=<user>/<password>@<db_address>:<db_port>:<db_name>```
* ```--user-schema=<user>/<password>```
* ```--rpt-schema=<user>/<password>```
* ```--pkg-schema=<user>/<password>```
* ```--perfstat-schema=<user>/<password>```

When passwords for all schemas are the same (may be common for local dev env), only --owner-schema parameter may be used, other schema names will be generated by adding _user, _rpt and _pkg prefixes to the owner schema

* ```--scripts-dir=<location of the directory with DB scripts>```
* ```--gen-ddl``` generate DDL for objects created with new scripts
* ```--exec``` execute new scripts
* ```--omit-changed``` do not check for sciprt changes. Script modifications detection is based on hash code calc, omiting this procedure may improove perfomance
* ```--ignore-errors``` do not stop on errors 
* ```--no-color``` do not color output

### Usage Scenarious
**1. Execute new scripts in local dev env:**

```java -jar scmdb.jar --owner-schema=vqs_p01_epm/vepm@localhost:1521:orclpdb --scripts-dir=./db/scripts --exec```

On first start for the DB schema, scmdb creates ```db_script``` table and populates it with all the scripts from ```--scripts-dir```. Subsequnt starts with ```--exec``` option will execute new scripts added to the file system, but not present in ```db_script``` table

**2. Update DDL files before committing new DB scripts into VCS.** DDL files are supposed to be located in ../ddl/packages, ../ddl/tables and ../ddl/views folders (relative to the --scripts-dir). DDLs will be generated for new DB scripts not yet executed with scmdb (no records in db_script table), but changes should be already made for DB schema:

```java -jar scmdb.jar --owner-schema=vqs_p01_epm/vepm@localhost:1521:orclpdb --scripts-dir=./db/scripts --gen-ddl```

DDL generation works by regexping new DB scripts for DB objects creation/modification and then extracting DDLs from the DB

**3. Execute DB scripts in production environment during new version deployment** (do not stop on errors, do not execute rollbacks):

```java -jar scmdb.jar --owner-schema=$ownerSchema --user-schema=$userSchema --rpt-schema=$rptSchema --pkg-schema=$pkgSchema --scripts-dir=db/scripts --no-color --exec --omit-changed --ignore-errors```
