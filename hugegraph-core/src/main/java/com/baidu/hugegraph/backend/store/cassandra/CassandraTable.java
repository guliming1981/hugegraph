package com.baidu.hugegraph.backend.store.cassandra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.Condition.Relation;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.query.Query.Order;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.type.HugeTypes;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.CopyUtil;
import com.datastax.driver.core.BatchStatement;
import com.datastax.driver.core.ColumnDefinitions.Definition;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.exceptions.InvalidQueryException;
import com.datastax.driver.core.querybuilder.Clause;
import com.datastax.driver.core.querybuilder.Delete;
import com.datastax.driver.core.querybuilder.Delete.Where;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.datastax.driver.core.schemabuilder.SchemaBuilder;
import com.google.common.collect.ImmutableList;

public abstract class CassandraTable {

    private static final Logger logger = LoggerFactory.getLogger(CassandraTable.class);

    protected String table;
    protected BatchStatement batch;

    public CassandraTable(String table) {
        this.table = table;
        this.batch = new BatchStatement();
    }

    public Iterable<BackendEntry> query(Session session, Query query) {
        List<BackendEntry> rs = new LinkedList<>();

        List<Select> selections = query2Select(query);

        for (Select selection : selections) {
            ResultSet results = session.execute(selection);
            rs.addAll(this.results2Entries(query.resultType(), results));
        }

        logger.debug("return {} for query {}", rs, query);
        return rs;
    }

    protected List<Select> query2Select(Query query) {
        // table
        Select select = QueryBuilder.select().from(this.table);

        // limit
        if (query.limit() != Query.NO_LIMIT) {
            // TODO: improve the factor
            // a vertex/edge is stored as multiple lines
            // we assume there is 100 number of props in a vertex/edge
            final int PROPS_PER_ELEMENT = 100;
            select.limit((int) query.limit() * PROPS_PER_ELEMENT);
        }

        // NOTE: Cassandra does not support query.offset()
        if (query.offset() != 0) {
            logger.warn("Query offset are not currently supported"
                    + " on Cassandra strore, it will be ignored");
        }

        // order-by
        for (Entry<HugeKeys, Order> order : query.orders().entrySet()) {
            if (order.getValue() == Order.ASC) {
                select.orderBy(QueryBuilder.asc(order.getKey().name()));
            } else {
                assert order.getValue() == Order.DESC;
                select.orderBy(QueryBuilder.desc(order.getKey().name()));
            }
        }

        // by id
        List<Select> ids = this.queryId2Select(query, select);

        if (query.conditions().isEmpty()) {
            logger.debug("query only by id(s): {}", ids);
            return ids;
        } else {
            List<Select> conds = new ArrayList<Select>(ids.size());
            for (Select selection : ids) {
                // by condition
                conds.addAll(this.queryCondition2Select(query, selection));
            }
            logger.debug("query by conditions: {}", conds);
            return conds;
        }
    }

    protected List<Select> queryId2Select(Query query, Select select) {
        // query by id(s)
        if (query.ids().isEmpty()) {
            return ImmutableList.of(select);
        }

        List<List<String>> ids = new ArrayList<>(query.ids().size());
        for (Id id : query.ids()) {
            ids.add(this.idColumnValue(id));
        }

        List<String> names = this.idColumnName();
        // query only by partition-key
        if (names.size() == 1) {
            List<String> idList = new ArrayList<>(ids.size());
            for (List<String> id : ids) {
                assert id.size() == 1;
                idList.add(id.get(0));
            }
            select.where(QueryBuilder.in(names.get(0), idList));
            return ImmutableList.of(select);
        }
        // query by partition-key + cluster-key
        else {
            // NOTE: Error of multi-column IN when including partition key:
            // error: multi-column relations can only be applied to cluster columns
            // when using: select.where(QueryBuilder.in(names, idList));
            // so we use multi-query instead
            List<Select> selections = new ArrayList<Select>(ids.size());
            for (List<String> id : ids) {
                assert names.size() == id.size();
                // NOTE: there is no Select.clone(), just use copy instead
                Select idSelection = CopyUtil.copy(select,
                        QueryBuilder.select().from(this.table));
                // NOTE: concat with AND relation
                // like: pk = id and ck1 = v1 and ck2 = v2
                for (int i = 0; i < names.size(); i++) {
                    idSelection.where(QueryBuilder.eq(names.get(i), id.get(i)));
                }
                selections.add(idSelection);
            }
            return selections;
        }
    }

    protected Collection<Select> queryCondition2Select(
            Query query, Select select) {
        // query by conditions
        List<Condition> conditions = query.conditions();
        for (Condition condition : conditions) {
            select.where(condition2Cql(condition));
        }
        return ImmutableList.of(select);
    }

    protected static Clause condition2Cql(Condition condition) {
        switch (condition.type()) {
            case AND:
                Condition.And and = (Condition.And) condition;
                // TODO: return QueryBuilder.and(and.left(), and.right());
                Clause left = condition2Cql(and.left());
                Clause right = condition2Cql(and.right());
                return (Clause) QueryBuilder.raw(String.format("%s AND %s",
                        left, right));
            case OR:
                throw new BackendException("Not support OR currently");
            case RELATION:
                Condition.Relation r = (Condition.Relation) condition;
                return relation2Cql(r);
            default:
                String msg = "Not supported condition: " + condition;
                throw new AssertionError(msg);
        }
    }

    protected static Clause relation2Cql(Relation relation) {
        String key = relation.key().toString();
        Object value = relation.value();

        // serialize value (TODO: should move to Serializer)
        if (value instanceof Id) {
            value = ((Id) value).asString();
        } else if (value instanceof Direction) {
            value = ((Direction) value).name();
        }

        switch (relation.relation()) {
            case EQ:
                return QueryBuilder.eq(key, value);
            case GT:
                return QueryBuilder.gt(key, value);
            case GTE:
                return QueryBuilder.gte(key, value);
            case LT:
                return QueryBuilder.lt(key, value);
            case LTE:
                return QueryBuilder.lte(key, value);
            case NEQ:
            default:
                throw new AssertionError("Not supported relation: " + relation);
        }
    }

    protected List<BackendEntry> results2Entries(HugeTypes resultType,
                                                 ResultSet results) {
        List<BackendEntry> entries = new LinkedList<>();

        Iterator<Row> iterator = results.iterator();
        while (iterator.hasNext()) {
            Row row = iterator.next();
            entries.add(result2Entry(resultType, row));
        }

        return this.mergeEntries(entries);
    }

    protected CassandraBackendEntry result2Entry(HugeTypes type, Row row) {
        CassandraBackendEntry entry = new CassandraBackendEntry(type);

        List<Definition> cols = row.getColumnDefinitions().asList();
        for (Definition col : cols) {
            String name = col.getName();
            String value = row.getString(name);
            HugeKeys key = HugeKeys.valueOf(name.toUpperCase());

            if (this.isColumnKey(key)) {
                entry.column(key, value);
            } else if (this.isCellKey(key)) {
                // about key: such as prop-key, now let's get prop-value by it
                // TODO: we should improve this code,
                // let Vertex and Edge implement results2Entries()
                HugeKeys cellKeyType = key;
                String cellKeyValue = value;
                HugeKeys cellValueType = this.cellValueType(cellKeyType);
                String cellValue = row.getString(cellValueType.name());

                entry.column(new CassandraBackendEntry.Property(
                        cellKeyType, cellKeyValue,
                        cellValueType, cellValue));
            } else {
                assert isCellValue(key);
            }
        }

        return entry;
    }

    protected List<String> idColumnName() {
        return ImmutableList.of(HugeKeys.NAME.name());
    }

    protected List<String> idColumnValue(Id id) {
        return ImmutableList.of(id.asString());
    }

    protected List<BackendEntry> mergeEntries(List<BackendEntry> entries) {
        return entries;
    }

    protected boolean isColumnKey(HugeKeys key) {
        return true;
    }

    protected boolean isCellKey(HugeKeys key) {
        return false;
    }

    protected boolean isCellValue(HugeKeys key) {
        return false;
    }

    protected HugeKeys cellValueType(HugeKeys key) {
        return null;
    }

    public void insert(CassandraBackendEntry.Row entry) {
        assert entry.keys().size() + entry.cells().size() > 0;

        // insert keys
        if (entry.cells().isEmpty()) {
            Insert insert = QueryBuilder.insertInto(this.table);

            for (Entry<HugeKeys, String> k : entry.keys().entrySet()) {
                insert.value(k.getKey().name(), k.getValue());
            }

            this.batch.add(insert);
        }
        // insert keys + values
        else {
            for (CassandraBackendEntry.Property i : entry.cells()) {
                Insert insert = QueryBuilder.insertInto(this.table);

                for (Entry<HugeKeys, String> k : entry.keys().entrySet()) {
                    insert.value(k.getKey().name(), k.getValue());
                }

                insert.value(i.nameType().name(), i.name());
                insert.value(i.valueType().name(), i.value());
                this.batch.add(insert);
            }
        }
    }

    public void delete(CassandraBackendEntry.Row entry) {
        // delete by id
        if (entry.keys().isEmpty()) {
            List<String> idNames = this.idColumnName();
            List<String> idValues = this.idColumnValue(entry.id());
            assert idNames.size() == idValues.size();

            Delete delete = QueryBuilder.delete().from(this.table);
            for (int i = 0; i < idNames.size(); i++) {
                delete.where(QueryBuilder.eq(idNames.get(i), idValues.get(i)));
            }

            this.batch.add(delete);
        }
        // delete just by keys (TODO: improve EXIST)
        else if (entry.cells().isEmpty() || entry.cells().contains(
                CassandraBackendEntry.Property.EXIST)) {
            Delete delete = QueryBuilder.delete().from(this.table);
            for (Entry<HugeKeys, String> k : entry.keys().entrySet()) {
                delete.where(QueryBuilder.eq(k.getKey().name(), k.getValue()));
            }

            this.batch.add(delete);
        }
        // delete by key + value-key (such as vertex property)
        else {
            for (CassandraBackendEntry.Property i : entry.cells()) {
                Delete delete = QueryBuilder.delete().from(this.table);
                Where where = delete.where();

                for (Entry<HugeKeys, String> k : entry.keys().entrySet()) {
                    where.and(QueryBuilder.eq(k.getKey().name(), k.getValue()));
                }

                where.and(QueryBuilder.eq(i.nameType().name(), i.name()));

                this.batch.add(delete);
            }
        }
    }

    public void commit(Session session) {
        if (session.isClosed()) {
            throw new BackendException("Session has been closed");
        }

        try {
            logger.debug("commit statements: {}", this.batch.getStatements());
            session.execute(this.batch);
            this.batch.clear();
        } catch (InvalidQueryException e) {
            logger.error("Failed to commit statements due to:", e);
            throw new BackendException("Failed to commit statements: "
                    + this.batch.getStatements());
        }
    }

    public boolean hasChanged() {
        return this.batch.size() > 0;
    }

    protected void createTable(Session session,
                               HugeKeys[] columns,
                               HugeKeys[] primaryKeys) {
        DataType[] columnTypes = new DataType[columns.length];
        for (int i = 0; i < columns.length; i++) {
            columnTypes[i] = DataType.text();
        }
        this.createTable(session, columns, columnTypes, primaryKeys);
    }

    protected void createTable(Session session,
                               HugeKeys[] columns,
                               DataType[] columnTypes,
                               HugeKeys[] primaryKeys) {
        // TODO: to make it more clear.
        assert (primaryKeys.length > 0);
        HugeKeys[] partitionKeys = new HugeKeys[] {primaryKeys[0]};
        HugeKeys[] clusterKeys = null;
        if (primaryKeys.length > 1) {
            clusterKeys = Arrays.copyOfRange(
                    primaryKeys, 1, primaryKeys.length);
        } else {
            clusterKeys = new HugeKeys[] {};
        }
        this.createTable(session, columns, columnTypes, partitionKeys, clusterKeys);
    }

    protected void createTable(Session session,
                               HugeKeys[] columns,
                               HugeKeys[] pKeys,
                               HugeKeys[] cKeys) {
        DataType[] columnTypes = new DataType[columns.length];
        for (int i = 0; i < columns.length; i++) {
            columnTypes[i] = DataType.text();
        }
        this.createTable(session, columns, columnTypes, pKeys, cKeys);
    }

    protected void createTable(Session session,
                               HugeKeys[] columns,
                               DataType[] columnTypes,
                               HugeKeys[] pKeys,
                               HugeKeys[] cKeys) {

        assert (columns.length == columnTypes.length);

        StringBuilder sb = new StringBuilder(128 + columns.length * 64);

        // table
        sb.append("CREATE TABLE IF NOT EXISTS ");
        sb.append(this.table);
        sb.append("(");

        // columns
        for (int i = 0; i < columns.length; i++) {
            // column name
            sb.append(columns[i].name());
            sb.append(" ");
            // column type
            sb.append(columnTypes[i].asFunctionParameterString());
            sb.append(", ");
        }

        // primary keys
        sb.append("PRIMARY KEY (");

        // partition keys
        sb.append("(");
        for (HugeKeys i : pKeys) {
            if (i != pKeys[0]) {
                sb.append(", ");
            }
            sb.append(i.name());
        }
        sb.append(")");

        // clustering keys
        for (HugeKeys i : cKeys) {
            sb.append(", ");
            sb.append(i.name());
        }

        // end of primary keys
        sb.append(")");

        // end of table declare
        sb.append(");");

        logger.info("Create table: {}", sb);
        session.execute(sb.toString());
    }

    protected void dropTable(Session session) {
        logger.info("Drop table: {}", this.table);
        session.execute(SchemaBuilder.dropTable(this.table).ifExists());
    }

    protected void createIndex(Session session, String indexName, HugeKeys column) {

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE INDEX ");
        sb.append(indexName);
        sb.append(" ON ");
        sb.append(this.table);
        sb.append("(");
        sb.append(column.name());
        sb.append(");");

        logger.info("create index: {}", sb);
        session.execute(sb.toString());
    }

    /*************************** abstract methods ***************************/

    public abstract void init(Session session);

    public void clear(Session session) {
        this.dropTable(session);
    }
}