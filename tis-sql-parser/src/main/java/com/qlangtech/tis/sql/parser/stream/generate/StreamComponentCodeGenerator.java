package com.qlangtech.tis.sql.parser.stream.generate;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.qlangtech.tis.manage.common.TisUTF8;
import com.qlangtech.tis.manage.common.incr.StreamContextConstant;
import com.qlangtech.tis.sql.parser.SqlTaskNodeMeta;
import com.qlangtech.tis.sql.parser.TisGroupBy;
import com.qlangtech.tis.sql.parser.er.*;
import com.qlangtech.tis.sql.parser.meta.PrimaryLinkKey;
import com.qlangtech.tis.sql.parser.tuple.creator.EntityName;
import com.qlangtech.tis.sql.parser.tuple.creator.impl.*;
import com.qlangtech.tis.sql.parser.visitor.FunctionVisitor;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

//java runtime compiler: https://blog.csdn.net/lmy86263/article/details/59742557

/**
 * 基于SQL的增量组件代码生成器，（scala代码）
 *
 * @author 百岁（baisui@qlangtech.com）
 * @date 2019年10月11日
 */
public class StreamComponentCodeGenerator extends StreamCodeContext {

    private final SqlTaskNodeMeta.SqlDataFlowTopology topology;
    private final boolean excludeFacadeDAOSupport;


    private final List<FacadeContext> daoFacadeList;
    private final Optional<ERRules> erRules;


    private static final Logger logger = LoggerFactory.getLogger(StreamComponentCodeGenerator.class);

    public StreamComponentCodeGenerator(String collectionName, long timestamp,
                                        List<FacadeContext> daoFacadeList, SqlTaskNodeMeta.SqlDataFlowTopology topology) {
        this(collectionName, timestamp, daoFacadeList, topology, false);
    }

    public StreamComponentCodeGenerator(String collectionName, long timestamp,
                                        List<FacadeContext> daoFacadeList, SqlTaskNodeMeta.SqlDataFlowTopology topology, boolean excludeFacadeDAOSupport) {
        super(collectionName, timestamp);
        this.erRules = ERRules.getErRule(topology.getName());
        this.topology = topology;
        this.daoFacadeList = daoFacadeList;
        this.excludeFacadeDAOSupport = excludeFacadeDAOSupport;
    }


    private void generateFunctionCallScript(FunctionVisitor.FuncFormat rr, final PropGetter propGetter) {
        rr.startLine("//===================================");
        final AtomicBoolean isFirstReturn = new AtomicBoolean(true);
        final FunctionVisitor.IToString returnVal = new FunctionVisitor.IToString() {
            @Override
            public String toString() {
                // return isFirstReturn.get() ? "return " : StringUtils.EMPTY;
                return StringUtils.EMPTY;
            }
        };
        try {
            if (propGetter.isLastFunctInChain()) {
                rr.startLine(returnVal);
            } else {

                rr.startLine("putMediaResult(\"" + propGetter.getOutputColName().getAliasName() + "\", //");
                // rr.startLine("() ->");
                propGetter.getGroovyScript(rr, false);
                rr.startLine(")");
                return;
            }

            // 既要是最后一个Func节点 且 要是多源节点
            if (propGetter.shallCallableProcess()) {
                // 返回callable的结构
                // rr.methodBody(false, "new Callable<Object>()", (kk) -> {
                // kk.methodBody("public Object call() throws Exception", (bb)
                // -> {
                // bb.appendLine("return ");
                // propGetter.getGroovyScript(bb);
                // bb.append(";");
                // });
                // });

                // rr.methodBody(false, "new Callable<Object>()", (kk) -> {
                // kk.methodBody("public Object call() throws Exception", (bb)
                // -> {
                // bb.appendLine("return ");
                propGetter.getGroovyScript(rr, false);
                // bb.append(";");
                // });
                // });
            } else {

                if (propGetter.isLastFunctInChain() && !propGetter.isGroupByFunction()
                        && propGetter.isNextGroupByFunction()) {
                    isFirstReturn.set(false);
                    final PropGetter nextGroup = propGetter.getNextGroupByPropGetter();
                    if (nextGroup == null) {
                        throw new IllegalStateException("NextGroupByPropGetter can not be null");
                    }

                    // rr.methodBody("for(Map.Entry<GroupKey, GroupValues> entry
                    // : "
                    // +
                    // nextGroup.getFunctionDataTuple().getGroupBy().get().getGroupAggrgationName()
                    // + ".entrySet())", (r) -> {
                    // r.append("return ");
                    // propGetter.getGroovyScript(r);
                    // r.append(";");
                    // });
                    rr.startLine("var result:Any = null");
                    rr.startLine("breakable {");
                    rr.methodBody("for((k:GroupKey, v:GroupValues) <- "
                                    + nextGroup.getFunctionDataTuple().getGroupBy().get().getGroupAggrgationName() + ")",
                            (r) -> {
                                r.append("result = ");
                                propGetter.getGroovyScript(r, true);
                                rr.startLine("break");
                            });
                    rr.startLine(" }");
                    rr.startLine("result");
                } else {
                    propGetter.getGroovyScript(rr, false);
                }

            }
            // rr.append(";");
        } finally {
            rr.startLine("//===================================");
        }

    }

    /**
     * 开始生成增量执行脚本（scala版本）
     *
     * @throws Exception
     */
    public void build() throws Exception {
        final PrintStream traversesAllNodeOut = new PrintStream(new File("./traversesAllNode.txt"));

        try {
            TableTupleCreator finalTableNode = this.parseFinalSqlTaskNode();
            ERRules erR = erRules.get();
            TaskNodeTraversesCreatorVisitor visitor = new TaskNodeTraversesCreatorVisitor(erR);
            finalTableNode.accept(visitor);

            Map<TableTupleCreator, List<ValChain>> tabTriggers = visitor.getTabTriggerLinker();
            PropGetter last = null;
            PropGetter first = null;
            Optional<TableRelation> firstParent = null;
            FunctionVisitor.FuncFormat aliasListBuffer = new FunctionVisitor.FuncFormat();


            for (Map.Entry<TableTupleCreator, List<ValChain>> e : tabTriggers.entrySet()) {
                final EntityName entityName = e.getKey().getEntityName();
                final Set<String> relevantCols = e.getValue().stream()
                        .map((rr) -> rr.last().getOutputColName().getName()).collect(Collectors.toSet());

                //>>>>>>>>>>>>>
                // 包括主表的和子表的
                final Set<String> linkCols = Sets.newHashSet();
                final List<TableRelation> allParent = erR.getAllParent(entityName);
                for (TableRelation r : allParent) {
                    linkCols.addAll(r.getJoinerKeys().stream().map((j) -> j.getChildKey()).collect(Collectors.toList()));
                }

                final List<TableRelation> allChild = erR.getChildTabReference(entityName);
                for (TableRelation r : allChild) {
                    linkCols.addAll(r.getJoinerKeys().stream().map((j) -> j.getParentKey()).collect(Collectors.toList()));
                }
                //<<<<<<<<<<<<<<

                traversesAllNodeOut.println("<<<<<<<%%%%%%%%export:" + entityName);

                aliasListBuffer.append("val ").append(entityName.getJavaEntityName())
                        .append("Builder:AliasList.Builder = builder.add(\"").append(entityName.getTabName())
                        .append("\")");
                final boolean isTriggerIgnore = erR.isTriggerIgnore(entityName);
                if (isTriggerIgnore) {
                    aliasListBuffer.append(".setIgnoreIncrTrigger()");
                }

                // 设置是否是主键
                boolean isPrimaryTable = false;
                Optional<TableMeta> primaryFind = erR.getPrimaryTab(entityName);
                PrimaryTableMeta ptab = null;
                if (primaryFind.isPresent()) {
                    isPrimaryTable = true;
                    ptab = (PrimaryTableMeta) primaryFind.get();
                    aliasListBuffer.append(".setPrimaryTableOfIndex()");
                }

                aliasListBuffer.returnLine();

                if (!isPrimaryTable) {
                    // 设置主键
                    aliasListBuffer
                            .methodBody(entityName.javaPropTableName() + "ColEnum.getPKs().forEach((r) =>", (r) -> {
                                r.startLine(entityName.getJavaEntityName()).append("Builder.add(r.getName().PK())");
                            }).append(")");
                }


                aliasListBuffer.startLine(entityName.getJavaEntityName()).append("Builder.add(").append(" // ").returnLine();

                boolean timestampVerColumnProcessed = false;
                // 判断out的列是否已经输出
                Set<String> outCol = Sets.newHashSet();
                boolean firstAdd = true;
                for (ValChain tupleLink : e.getValue()) {
                    first = tupleLink.first();
                    last = tupleLink.last();
                    traversesAllNodeOut.println("last:" + (last == null ? "null" : last.getIdentityName()));
                    traversesAllNodeOut.println("first:" + (first == null ? "null" : first.getIdentityName()));
                    traversesAllNodeOut.println(Joiner.on("\n-->").join(tupleLink.mapChainValve((r/* PropGetter */) -> {
                        // FunctionVisitor.FuncFormat rr = new
                        // FunctionVisitor.FuncFormat();
                        // r.getGroovyScript(rr);
                        return r.getIdentityName();// + "\n" + rr;
                    }).iterator()));

                    traversesAllNodeOut.println("-------------------------------");

                    boolean haveAdd = outCol.add(first.getOutputColName().getAliasName());

                    if (!firstAdd) {
                        aliasListBuffer.startLine(",");
                    } else {
                        firstAdd = false;
                    }


                    if (tupleLink.useAliasOutputName()) {
                        aliasListBuffer.append("(\"").append(last.getOutputColName().getName()).append("\", \"")
                                .append(first.getOutputColName().getAliasName()).append("\")");
                    } else {
                        aliasListBuffer.append("(\"").append(last.getOutputColName().getName()).append("\")");
                    }

                    // 如果是主表就在通过单独的列meta配置中的信息来设置主键，在实际例子中发现表中使用了联合主键，在运行的时候会出错
                    if (isPrimaryTable && ptab.isPK(last.getOutputColName().getName())) {
                        aliasListBuffer.append(".PK()");
                    }

                    if (erR.isTimestampVerColumn(entityName, last.getOutputColName().getName())) {
                        // 时间戳字段
                        aliasListBuffer.append(".timestampVer()");
                        timestampVerColumnProcessed = true;
                    }

                    if (!haveAdd) {
                        aliasListBuffer.append(".notCopy()");

                    } else if (tupleLink.hasFuncTuple()) {

                        AtomicBoolean shallCallableProcess = new AtomicBoolean(false);

                        final FunctionVisitor.IToString shallCallableProcessToken = new FunctionVisitor.IToString() {
                            @Override
                            public String toString() {
                                return shallCallableProcess.get() ? "c" : "t";
                            }
                        };

                        final FunctionVisitor.IToString fieldValue = new FunctionVisitor.IToString() {
                            @Override
                            public String toString() {
                                return shallCallableProcess.get() ? StringUtils.EMPTY : ", fieldValue";
                            }
                        };

                        aliasListBuffer.append(".").append(shallCallableProcessToken).append("(")//
                                .append("(" + FunctionVisitor.ROW_KEY).append(fieldValue).append(")")
                                .methodBody(false, " => ", (rr) -> {
                                    final AtomicInteger index = new AtomicInteger();
                                    final AtomicReference<String> preGroupAggrgationName = new AtomicReference<>();
                                    tupleLink.chainStream()
                                            .filter((r) -> r.getTupleCreator() != null
                                                    && r.getTupleCreator() instanceof FunctionDataTupleCreator)
                                            .forEach((r) -> {
                                                final FunctionDataTupleCreator tuple = (FunctionDataTupleCreator) r
                                                        .getTupleCreator();
                                                final PropGetter propGetter = r;
                                                MapDataMethodCreator mapDataMethodCreator = null;
                                                Optional<TisGroupBy> group = tuple.getGroupBy();
                                                if (propGetter.shallCallableProcess()) {
                                                    shallCallableProcess.set(true);
                                                }
                                                if (index.getAndIncrement() < 1) {

                                                    if (r.isGroupByFunction()) {
                                                        TisGroupBy groups = group.get();
                                                        mapDataMethodCreator = addMapDataMethodCreator(entityName,
                                                                groups, relevantCols);

                                                        // 第一次
                                                        // rr.startLine("Map<GroupKey
                                                        // /*")
                                                        // .append(groups.getGroupsLiteria())
                                                        // .append(" */,
                                                        // GroupValues> ")
                                                        // .append(groups.getGroupAggrgationName()).append("
                                                        // = ")
                                                        // .append(mapDataMethodCreator.getMapDataMethodName())
                                                        // .append("(").append(FunctionVisitor.ROW_KEY)
                                                        // .append(")").returnLine().returnLine();

                                                        rr.startLine("val ").append(groups.getGroupAggrgationName())
                                                                .append(":Map[GroupKey /*")
                                                                .append(groups.getGroupsLiteria())
                                                                .append("*/, GroupValues]  = ")
                                                                .append(mapDataMethodCreator.getMapDataMethodName())
                                                                .append("(").append(FunctionVisitor.ROW_KEY).append(")")
                                                                .returnLine().returnLine();

                                                        preGroupAggrgationName.set(groups.getGroupAggrgationName());

                                                        generateCreateGroupResultScript(rr, propGetter, groups);

                                                        // if (propGetter.isLastFunctInChain()) {
                                                        // rr.startLine("return null");
                                                        // }

                                                    } else {
                                                        // 不需要反查维表执行函数
                                                        // 测试
                                                        generateFunctionCallScript(rr, propGetter);
                                                    }

                                                } else {
                                                    if (r.isGroupByFunction()) {
                                                        TisGroupBy groups = group.get();

                                                        // rr.startLine("final
                                                        // Map<GroupKey /* ")
                                                        // .append(groups.getGroupsLiteria())
                                                        // .append(" */,
                                                        // GroupValues> ")
                                                        // .append(groups.getGroupAggrgationName())
                                                        // .append(" =
                                                        // reduceData(")
                                                        // .append(preGroupAggrgationName.get()).append(",
                                                        // 0);\n");
                                                        rr.append("val ").append(groups.getGroupAggrgationName())
                                                                .append(": Map[GroupKey /* ")
                                                                .append(groups.getGroupsLiteria())
                                                                .append(" */, GroupValues] ").append(" = reduceData(")
                                                                .append(preGroupAggrgationName.get()).append(", " + groups.getGroupKeyAsParamsLiteria() + ")\n");

                                                        generateCreateGroupResultScript(rr, propGetter, groups);

                                                        preGroupAggrgationName.set(groups.getGroupsLiteria());
                                                        // if (propGetter.isLastFunctInChain()) {
                                                        // rr.startLine("return null;");
                                                        // }
                                                    } else {

                                                        generateFunctionCallScript(rr, propGetter);
                                                    }

                                                }

                                            });

                                }).append(")/*end .t()*/");

                    }

                    // aliasListBuffer.append(" // \n");
                }


                //>>>>>>>>>>>>>如果外键不在上面的处理列中，就在列处理中添加上，不然在getPk中会出现空指针异常
//                firstParent = erRules.get().getFirstParent(entityName.getTabName());
//                if (firstParent.isPresent()) {
//                    TableRelation relation = firstParent.get();
//                    for (JoinerKey jk : relation.getJoinerKeys()) {
//                        if (!relevantCols.contains(jk.getChildKey())) {
//                            if (!firstAdd) {
//                                aliasListBuffer.appendLine(",");
//                            } else {
//                                firstAdd = false;
//                                aliasListBuffer.returnLine();
//                            }
//                            aliasListBuffer.append("(\"")
//                                    .append(jk.getChildKey()).append("\").notCopy() // FK to " + relation.getParent().parseEntityName());
//                        }
//                    }
//                }

                for (String linkKey : linkCols) {
                    if (!relevantCols.contains(linkKey)) {
                        if (!firstAdd) {
                            aliasListBuffer.appendLine(",");
                        } else {
                            firstAdd = false;
                            aliasListBuffer.returnLine();
                        }
                        aliasListBuffer.append("(\"")
                                .append(linkKey).append("\").notCopy()  ");

                        if (erR.isTimestampVerColumn(entityName, linkKey)) {
                            aliasListBuffer.append(".timestampVer()");
                            timestampVerColumnProcessed = true;
                        }

                        aliasListBuffer.append("// FK or primay key");
                    }
                }


                // timestampVer标记没有添加，且本表不要监听增量消息
                if (!timestampVerColumnProcessed && !erR.isTriggerIgnore(entityName)) {
                    if (!firstAdd) {
                        aliasListBuffer.appendLine(",");
                    } else {
                        firstAdd = false;
                        aliasListBuffer.returnLine();
                    }
                    aliasListBuffer.append("(\"")
                            .append(erR.getTimestampVerColumn(entityName)).append("\").notCopy().timestampVer() //gencode9 ");
                }

                //>>>>>>>>>>>>>
                aliasListBuffer.appendLine(");\n");
                traversesAllNodeOut.println("======================================>>>>>>>>>");

                //>>>>>>>>>>>>>
                // List<TableRelation> allParent = erR.getAllParent(entityName);
                for (TableRelation r : allParent) {
                    //addParentTabRef(EntityName parentName, List<JoinerKey> joinerKeys)
                    aliasListBuffer.append(entityName.getJavaEntityName())
                            .append("Builder.addParentTabRef(").append(r.getParent().parseEntityName().createNewLiteriaToken())
                            .append(",").append(JoinerKey.createListNewLiteria(r.getJoinerKeys())).append(")").returnLine();
                }

                // List<TableRelation> allChild = erR.getChildTabReference(entityName);
                for (TableRelation r : allChild) {
                    aliasListBuffer.append(entityName.getJavaEntityName())
                            .append("Builder.addChildTabRef(").append(r.getChild().parseEntityName().createNewLiteriaToken())
                            .append(",").append(JoinerKey.createListNewLiteria(r.getJoinerKeys())).append(")").returnLine();
                }
                //<<<<<<<<<<<<<<

                if (!this.excludeFacadeDAOSupport) {
                    // create setGetterRowsFromOuterPersistence
                    aliasListBuffer.append(entityName.getJavaEntityName())
                            .append("Builder.setGetterRowsFromOuterPersistence(/*gencode5*/")
                            .methodBody(" (rowTabName, rvals, pk ) =>", (f) -> {
                                Set<String> selectCols = Sets.union(relevantCols, linkCols);
                                if (primaryFind.isPresent()) {
                                    // 主索引表
                                    PrimaryTableMeta ptabMeta = (PrimaryTableMeta) primaryFind.get();

                                    f.appendLine(entityName.buildDefineRowMapListLiteria());
                                    f.appendLine(entityName.buildDefineCriteriaEqualLiteria());

                                    List<PrimaryLinkKey> primaryKeyNames = ptabMeta.getPrimaryKeyNames();
                                    for (PrimaryLinkKey linkKey : primaryKeyNames) {
                                        if (!linkKey.isPk()) {
                                            TisGroupBy.TisColumn routerKey = new TisGroupBy.TisColumn(linkKey.getName());
                                            f.appendLine(routerKey.buildDefineGetPkRouterVar());
                                        }
                                    }
                                    f.appendLine(entityName.buildCreateCriteriaLiteria());
                                    for (PrimaryLinkKey linkKey : primaryKeyNames) {
                                        if (linkKey.isPk()) {
                                            TisGroupBy.TisColumn pk = new TisGroupBy.TisColumn(linkKey.getName());
                                            f.append(pk.buildPropCriteriaEqualLiteria("pk.getValue()"));
                                        } else {
                                            TisGroupBy.TisColumn pk = new TisGroupBy.TisColumn(linkKey.getName());
                                            f.append(pk.buildPropCriteriaEqualLiteria());
                                        }
                                    }
                                    f.appendLine(entityName.buildAddSelectorColsLiteria(selectCols));
                                    f.appendLine(entityName.buildExecuteQueryDAOLiteria());
                                    f.appendLine(entityName.entities());
                                } else {
                                    if (allChild.size() > 0) {
                                        f.appendLine(entityName.buildDefineRowMapListLiteria());
                                        f.appendLine(entityName.buildDefineCriteriaEqualLiteria()).returnLine();
                                        f.appendLine(entityName.buildAddSelectorColsLiteria(selectCols));
                                        f.methodBody("rowTabName match ", (ff) -> {
                                            for (TableRelation rel : allChild) {
                                                EntityName childEntity = rel.getChild().parseEntityName();

                                                ff.methodBody("case \"" + childEntity.getTabName() + "\" =>", (fff) -> {
                                                    fff.appendLine(entityName.buildCreateCriteriaLiteria());
                                                    for (JoinerKey jk : rel.getJoinerKeys()) {
                                                        TisGroupBy.TisColumn k = new TisGroupBy.TisColumn(jk.getParentKey());
                                                        fff.append(k.buildPropCriteriaEqualLiteria("rvals.getColumn(\"" + jk.getChildKey() + "\")"));
                                                    }
                                                    fff.returnLine();

                                                    fff.appendLine(entityName.buildExecuteQueryDAOLiteria());
                                                    fff.appendLine(entityName.entities());
                                                });
                                            }
                                            ff.appendLine("case unexpected => null");
                                        });
                                    } else {
                                        f.appendLine(" null");
                                    }
                                }
                            }).appendLine(") // end setGetterRowsFromOuterPersistence").returnLine().returnLine();
                }
            }


            MergeData mergeData = new MergeData(this.collectionName, mapDataMethodCreatorMap, aliasListBuffer,
                    tabTriggers, this.daoFacadeList, erRules.get(), this.excludeFacadeDAOSupport);
            mergeGenerate(mergeData);
        } finally {
            // traversesAllNodeOut.close();
            IOUtils.closeQuietly(traversesAllNodeOut);
        }

    }

    /**
     * 生成spring等增量应用启动需要的配置文件
     */
    public void generateConfigFiles() throws Exception {


        MergeData mergeData = new MergeData(this.collectionName, mapDataMethodCreatorMap, new FunctionVisitor.FuncFormat(),
                Collections.emptyMap(), this.daoFacadeList, this.erRules.get(), this.excludeFacadeDAOSupport);


        File parentDir =
                new File(getSpringConfigFilesDir()
                        , "com/qlangtech/tis/realtime/transfer/" + this.collectionName);

        FileUtils.forceMkdir(parentDir);

        this.mergeGenerate(mergeData
                , "/com/qlangtech/tis/classtpl/app-context.xml.vm"
                , new File(parentDir, "app-context.xml"));

        this.mergeGenerate(mergeData
                , "/com/qlangtech/tis/classtpl/field-transfer.xml.vm"
                , new File(parentDir, "field-transfer.xml"));
    }

    /**
     * Spring config file root dir
     *
     * @return
     */
    public File getSpringConfigFilesDir() {
        return new File(StreamContextConstant.getStreamScriptRootDir(this.collectionName, this.timestamp), "scriptconfig");
    }

    public static class MapDataMethodCreator {
        // 需要聚合的表
        private final EntityName entityName;
        private final TisGroupBy groups;
        private final Set<String> relefantCols;
        private final ERRules erRules;

        public MapDataMethodCreator(EntityName entityName, TisGroupBy groups, ERRules erRules, Set<String> relefantCols) {
            super();
            this.entityName = entityName;
            this.groups = groups;
            this.erRules = erRules;
            this.relefantCols = relefantCols;
        }

//        public final String capitalizeEntityName() {
//            return StringUtils.capitalize(entityName.getTabName());
//        }

        // 实体复数
//        public String entities() {
//            return this.entityName.getTabName() + "s";
//        }

        public String getMapDataMethodName() {
            return "map" + this.entityName.capitalizeEntityName() + "Data";
        }


        /**
         * @return
         */
        public String getGenerateMapDataMethodBody() {

            FunctionVisitor.FuncFormat funcFormat = new FunctionVisitor.FuncFormat();

            funcFormat.appendLine("val " + this.entityName.entities()
                    + "ThreadLocal : ThreadLocal[Map[GroupKey, GroupValues]]  = addThreadLocalVal()");

            funcFormat.returnLine();

            // funcFormat.methodBody( //
            // "private Map<GroupKey, GroupValues> " + getMapDataMethodName() +
            // "(IRowValueGetter "
            // + this.entityName.getTabName() + ")",
            funcFormat.methodBody( //
                    "private def " + getMapDataMethodName() + "( " + this.entityName.getJavaEntityName()
                            + " : IRowValueGetter) : scala.collection.mutable.Map[GroupKey, GroupValues] =",
                    (r) -> {
                        r.startLine("var result :scala.collection.mutable.Map[GroupKey, GroupValues] = " + this.entityName.entities()
                                + "ThreadLocal.get()");

                        //r.startLine("var " + this.entityName.entities() + ": List[" + ROW_MAP_CLASS_NAME + "]  = null");

                        r.startLine(this.entityName.buildDefineRowMapListLiteria());

                        r.methodBody("if (result != null)", (m) -> {
                            m.appendLine(" return result");
                        });
                        if (groups.getGroups().size() < 1) {
                            throw new IllegalStateException("groups.getGroups().size() can not small than 1");
                        }
                        TableRelation parentRel = null;
                        Optional<PrimaryTableMeta> ptab = this.erRules.isPrimaryTable(this.entityName.getTabName());
                        if (ptab.isPresent()) {
                            // 如果聚合表本身就是主表的话，那它只需要查询自己就行了
                            PrimaryTableMeta p = ptab.get();
                            parentRel = new TableRelation();
//                            parentRel.setParent(null);
//                            parentRel.setChild(null);
                            parentRel.setCardinality(TabCardinality.ONE_N.getToken());
                            // List<JoinerKey> joinerKeys = Lists.newArrayList();
                            parentRel.setJoinerKeys(p.getPrimaryKeyNames().stream().map((rr) -> new JoinerKey(rr.getName(), rr.getName())).collect(Collectors.toList()));
                        } else {
                            Optional<TableRelation> firstParentRel = this.erRules.getFirstParent(this.entityName.getTabName());
                            if (!firstParentRel.isPresent()) {
                                throw new IllegalStateException("first parent table can not be null ,child table:" + this.entityName);
                            }
                            parentRel = firstParentRel.get();
                        }

                        if (!parentRel.isCardinalityEqual(TabCardinality.ONE_N)) {
                            throw new IllegalStateException("rel" + parentRel + " execute aggreate mush be an rel cardinality:" + TabCardinality.ONE_N);
                        }

                        List<TisGroupBy.TisColumn> linkKeys = Lists.newArrayList();

                        try {
                            TisGroupBy.TisColumn col = null;
                            for (LinkKeys linkKey : parentRel.getCurrentTableRelation(true).getTailerKeys()) {
                                col = new TisGroupBy.TisColumn(linkKey.getHeadLinkKey());
                                linkKeys.add(col);
                                r.appendLine("val " + col.getJavaVarName() + ":String = " + entityName.getJavaEntityName()
                                        + ".getColumn(\"" + col.getColname() + "\")");
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(parentRel.toString(), e);
                        }


                        r.appendLine(this.entityName.buildDefineCriteriaEqualLiteria());

                        r.startLine(this.entityName.buildCreateCriteriaLiteria());


                        for (TisGroupBy.TisColumn g : linkKeys) {
                            r.append(g.buildPropCriteriaEqualLiteria());
                        }


                        // 外键查询键也要出现在select列中
                        final Set<String> selCols = Sets.newHashSet();
                        selCols.addAll(this.relefantCols);
                        this.groups.getGroups().stream().forEach((e) -> {
                            selCols.add(e.getColname());
                        });

                        r.startLine(this.entityName.buildAddSelectorColsLiteria(selCols));

                        r.startLine(this.entityName.buildExecuteQueryDAOLiteria());

                        r.startLine("result = scala.collection.mutable.Map[GroupKey, GroupValues]()");

                        r.startLine("var vals : Option[GroupValues] = null");
                        r.startLine("var groupKey: GroupKey = null");

                        r.buildRowMapTraverseLiteria(this.entityName, (m) -> {

                            m.startLine("groupKey = GroupKey.createCacheKey(") //
                                    .append(groups.getGroups().stream()
                                            .map((rr) -> "\"" + rr.getColname() + "\",r.getColumn(\"" + (rr.getColname()) + "\")").collect(Collectors.joining(",")));
                            m.append(")").returnLine();

                            m.appendLine("vals = result.get(groupKey)");
                            m.appendLine("if (vals.isEmpty) {");

                            m.appendLine("  result +=(groupKey.clone() -> new GroupValues(r)) ");
                            m.appendLine("}else{");
                            m.appendLine(" vals.get.addVal(r)");
                            m.appendLine("}");

                        });


//                        r.methodBody("for ( ( r:" + ROW_MAP_CLASS_NAME + ") <- " + this.entityName.entities() + ".asScala)",
//                                (m) -> {
//
//                                    m.startLine("groupKey = GroupKey.createCacheKey(")
//                                            .append(Joiner.on(",")
//                                                    .join(groups.getGroups().stream()
//                                                            .map((rr) -> "r.getColumn(\"" + (rr.getColname()) + "\")")
//                                                            .iterator()));
//                                    m.append(")").returnLine();
//
//                                    m.appendLine("vals = result.get(groupKey)");
//                                    m.appendLine("if (vals.isEmpty) {");
//                                    // m.appendLine(" vals = new
//                                    // GroupValues();");
//                                    // m.appendLine("
//                                    // result.put(groupKey.clone(),
//                                    // vals);");
//                                    m.appendLine("  result +=(groupKey.clone() -> new GroupValues(r)) ");
//                                    m.appendLine("}else{");
//                                    m.appendLine(" vals.get.addVal(r)");
//                                    m.appendLine("}");
//
//                                });

                        r.appendLine(this.entityName.entities() + "ThreadLocal.set(result);");
                        r.appendLine("return result;");

                    });

            return funcFormat.toString();
        }

    }

    private final Map<EntityName, MapDataMethodCreator> mapDataMethodCreatorMap = Maps.newHashMap();

    private MapDataMethodCreator addMapDataMethodCreator(EntityName entityName, TisGroupBy groups,
                                                         Set<String> relevantCols) {

        MapDataMethodCreator creator = mapDataMethodCreatorMap.get(entityName);
        if (creator == null) {
            creator = new MapDataMethodCreator(entityName, groups, this.erRules.get(), relevantCols);
            mapDataMethodCreatorMap.put(entityName, creator);
        }

        return creator;
    }

    private void generateCreateGroupResultScript(FunctionVisitor.FuncFormat rr, final PropGetter propGetter,
                                                 TisGroupBy groups) {
        if (propGetter.isLastFunctInChain()) {
            rr.startLine("var result:Any = null");
        }
        final AtomicBoolean hasBreak = new AtomicBoolean();
        rr.startLine(new FunctionVisitor.IToString() {
            public String toString() {
                return hasBreak.get() ? "breakable {" : StringUtils.EMPTY;
            }
        });

        rr.methodBody("for ((k:GroupKey, v:GroupValues)  <- " + groups.getGroupAggrgationName() + ")", (r) -> {
            final PropGetter pgetter = propGetter;

            if (propGetter.isLastFunctInChain()) {
                rr.startLine("result = ");
                hasBreak.set(true);
                propGetter.getGroovyScript(rr, true);
                rr.startLine("break");
                rr.returnLine();
            } else {

                PropGetter prev = propGetter.getPrev();
                while (prev != null) {
                    if (prev.getTupleCreator() instanceof FunctionDataTupleCreator) {
                        break;
                    }
                    prev = prev.getPrev();
                }

                boolean shallCallableProcess = (prev != null && prev.shallCallableProcess());
                if (shallCallableProcess) {
                    rr.startLine("putMediaResult(\"" + pgetter.getOutputColName().getAliasName() + "\", //");
                } else {
                    rr.startLine("v.putMediaData( // \n");
                    rr.startLine("\"").append(propGetter.getOutputColName().getAliasName()).append("\" // \n");
                    rr.startLine(", //\n");
                }

                propGetter.getGroovyScript(rr, true);// );

                if (shallCallableProcess) {
                    rr.startLine(") // end putMediaResult");
                    hasBreak.set(true);
                    rr.startLine("break");
                } else {
                    rr.startLine(") // end putMediaData\n");
                }
            }
        });

        rr.startLine(new FunctionVisitor.IToString() {
            public String toString() {
                return hasBreak.get() ? "} //end breakable" : StringUtils.EMPTY;
            }
        });

        if (propGetter.isLastFunctInChain()) {
            rr.startLine(" result //return");
        }

        rr.returnLine();
    }


    private VelocityContext createContext(MergeData mergeData) {

        VelocityContext velocityContext = new VelocityContext();
        velocityContext.put("config", mergeData);

        return velocityContext;
    }

    private void mergeGenerate(MergeData mergeData) {

        mergeGenerate(mergeData, "/com/qlangtech/tis/classtpl/mq_listener_scala.vm", getIncrScriptMainFile());
//        OutputStreamWriter writer = null;
//        // Reader tplReader = null;
//        try {
//
//            VelocityContext context = createContext(mergeData);
//
//            FileUtils.forceMkdir(this.incrScriptDir);
//
//            //File parent = getScalaStreamScriptDir(this.collectionName, this.timestamp);
//
//
//            File f = getIncrScriptMainFile();// new File(this.incrScriptDir, (mergeData.getJavaName()) + "Listener.scala");
//            //  System.out.println(f.getAbsolutePath());
//
//            writer = new OutputStreamWriter(FileUtils.openOutputStream(f), "utf8");
//
//
//            Template tpl = velocityEngine.getTemplate("/com/qlangtech/tis/classtpl/mq_listener_scala.vm", "utf8");
//            tpl.merge(context, writer);
//            // velocityEngine.evaluate(context, writer, "listener", tplReader);
//
//            writer.flush();
//
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//            IOUtils.closeQuietly(writer);
//            //IOUtils.closeQuietly(tplReader);
//        }
    }

    private void mergeGenerate(MergeData mergeData, String vmClasspath, File createdFile) {
        OutputStreamWriter writer = null;
        try {
            VelocityContext context = createContext(mergeData);

            FileUtils.forceMkdir(this.incrScriptDir);
            writer = new OutputStreamWriter(FileUtils.openOutputStream(createdFile), TisUTF8.get());
            Template tpl = velocityEngine.getTemplate(vmClasspath, TisUTF8.getName());
            tpl.merge(context, writer);
            writer.flush();

        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            IOUtils.closeQuietly(writer);
        }
    }

    public final File getIncrScriptMainFile() {
        return new File(this.incrScriptDir, (MergeData.getJavaName(this.collectionName)) + "Listener.scala");
    }


    private static final VelocityEngine velocityEngine;

    static {
        try {
            velocityEngine = new VelocityEngine();
            Properties prop = new Properties();
            prop.setProperty("runtime.log.logsystem.class", "org.apache.velocity.runtime.log.NullLogChute");
            prop.setProperty("resource.loader", "classpath");
            prop.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
            velocityEngine.init(prop);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    public TableTupleCreator parseFinalSqlTaskNode() throws Exception {
        return topology.parseFinalSqlTaskNode();
    }


    // public void testRootNode() throws Exception {
    //
    // SqlTaskNode taskNode =
    // SqlTaskNode.findTerminatorTaskNode(SqlTaskNode.parseTaskNodes());
    // Assert.assertNotNull(taskNode);
    //
    // Assert.assertEquals(totalpay_summary, taskNode.getExportName());
    //
    // }
    //
    // public void testParseNode() throws Exception {
    //
    // TableTupleCreator totalpaySummaryTuple =
    // this.parseSqlTaskNode(totalpay_summary);
    //
    // ColRef colRef = totalpaySummaryTuple.getColsRefs();
    //
    // for (Map.Entry<ColName /* colName */, IDataTupleCreator> entry :
    // colRef.colRefMap.entrySet()) {
    // // System.out.println(entry.getKey() + ":" + entry.getValue());
    // }
    // System.out.println("base===================================================");
    // for (Map.Entry<String /* base */, IDataTupleCreator> entry :
    // colRef.baseRefMap.entrySet()) {
    // // System.out.println(entry.getKey() + ":" + entry.getValue());
    // }
    //
    // }

}
