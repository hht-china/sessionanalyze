package com.hht.sessionanalyze.spark.session;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.hht.sessionanalyze.constant.Constants;
import com.hht.sessionanalyze.dao.*;
import com.hht.sessionanalyze.dao.factory.DAOFactory;
import com.hht.sessionanalyze.domain.*;
import com.hht.sessionanalyze.util.*;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import org.apache.spark.Accumulator;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.*;
import org.apache.spark.broadcast.Broadcast;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.storage.StorageLevel;
import scala.Tuple2;

import java.util.*;

/**
 * 获取用户访问session数据进行分析
 * 1、接收使用者创建的任务信息
 * 任务中的过滤条件有：
 * 时间范围：起始时间-结束时间
 * 年龄范围
 * 性别
 * 职业
 * 所在城市
 * 用户搜索的关键字
 * 点击品类
 * 点击商品
 * 2、spark作业是如何接收使用者创建的任务信息
 * 1）shell脚本通知-调用spark-submit脚本
 * 2）从mysql的task表中根据指定的taskId来获取任务信息
 * 3、spark作业开始数据分析
 */
public class UserVisitSessionAnalyzeSpark {
    public static void main(String[] args) {

        /*模板代码*/
        // 创建配置信息类
        SparkConf conf = new SparkConf()
                .setAppName(Constants.SPARK_APP_NAME_SESSION);
        SparkUtils.setMaster(conf);
        // 创建集群入口类
        JavaSparkContext sc = new JavaSparkContext(conf);
        // SparkSql的上下文对象
        SQLContext sqlContext = SparkUtils.getSQLContext(sc.sc());

        // 设置检查点
//        sc.checkpointFile("hdfs://node01:9000/.....");

        // 生成模拟数据
        SparkUtils.mockData(sc, sqlContext);

        // 创建获取任务信息的实例
        ITaskDAO taskDAO = DAOFactory.getTaskDAO();
        // 获取指定的任务，需要拿到taskId
        Long taskId = ParamUtils.getTaskIdFromArgs(args, Constants.SPARK_LOCAL_TASKID_SESSION);

        // 根据taskId获取任务信息
        Task task = taskDAO.findById(taskId);

        if (task == null) {
            System.out.println(new Date() + "亲，你给的taskId我并不能获取到信息哦~");
        }

        // 根据task去task_param字段去获取对应的任务信息
        // task_param字段里存的就是使用者提供的查询条件
        JSONObject taskParam = JSON.parseObject(task.getTaskParam());

        // 开始查询指定日期范围内的行为数据（点击、搜索、下单、支付）
        // 首先要从user_visit_action这张hive表中查询出指定日期范围内的行为数据
        JavaRDD<Row> actionRDD = SparkUtils.getActionRDDByDateRange(sqlContext, taskParam);

        // 生成session粒度的基础数据，得到的数据格式为：<sessionId, actionRDD>
        JavaPairRDD<String, Row> sessionId2ActionRDD = getSessionId2ActionRDD(actionRDD);

        // 对于以后经常用到的数据，最好缓存起来，这样便于以后快速的获取该数据
        sessionId2ActionRDD = sessionId2ActionRDD.cache();

        // 对行为数据进行聚合
        // 1、将行为数据按照sessionId进行分组
        // 2、行为数据RDD需要把用户信息获取到，此时需要用到join，这样得到session粒度的明细数据
        //      明细数据包含了session对应的用户基本信息：
        // 生成的格式为：<sessionId, (sessionId, searchKeywords, clickCategoryIds, age, professional, city, sex)>
        JavaPairRDD<String, String> sessionId2AggrInfoRDD =
                aggrgateBySession(sc, sqlContext, sessionId2ActionRDD);

        // 实现Accumulator累加器对数据字段的值进行累加
        Accumulator<String> sessionAggrStatAccumulator =
                sc.accumulator("", new SessionAggrStatAccumulator());


        // 以session粒度的数据进行聚合，按照使用者指定的筛选条件进行过滤
        JavaPairRDD<String, String> filteredSessionId2AggrInfoRDD =
                filteredSessionAndAggrStat(sessionId2AggrInfoRDD, taskParam, sessionAggrStatAccumulator);

        // 缓存过滤后的数据
        filteredSessionId2AggrInfoRDD =
                filteredSessionId2AggrInfoRDD.persist(StorageLevel.MEMORY_AND_DISK());

        // 生成一个公共的RDD， 通过筛选条件过滤出来的session得到访问明细
        JavaPairRDD<String, Row> sessionId2DetailRDD =
                getSessionId2DetailRDD(filteredSessionId2AggrInfoRDD, sessionId2ActionRDD);

        // 缓存
        sessionId2DetailRDD = sessionId2DetailRDD.cache();

        // 如果将上一个聚合的统计结果写入数据库，
        // 就必须给一个action算子进行触发后才能真正执行任务，从Accumulator中获取数据
        System.out.println(sessionId2DetailRDD.count());

        // 计算出各个范围的session占比，并写入数据库
        calculateAndPersistAggrStat(sessionAggrStatAccumulator.value(), task.getTaskid());

        /**
         * 按照时间比例随机抽取session
         * 1、首先计算出每个小时的session数量
         * 2、计算出每个小时的session数据量在一天的比例
         *      比如：要取出100条session数据
         *      表达式：当前小时抽取的session数量 = （每小时session的数据量 / session的总量）* 100
         * 3、按照比例进行随机抽取session
         */
        randomExtranctSession(sc, task.getTaskid(), filteredSessionId2AggrInfoRDD, sessionId2DetailRDD);

        /**
         * 计算top10热门品类
         * 1、获取通过筛选条件的session访问过的所有品类
         * 2、计算出session访问过的所有品类的点击、下单、支付次数，需要用到join
         * 3、自定义排序类
         * 4、将品类的点击下单支付次数封装到自定义排序key中
         * 5、使用sortByKey进行二次排序
         * 6、获取排序后的前10个品类：take(10)
         * 7、将top10热门品类及每个品类及每个品类的点击下单支付次数写入数据库
         */
        List<Tuple2<CategorySortKey, String>> top10CategroyList =
                getTop10Category(task.getTaskid(), sessionId2DetailRDD);

        /**
         * 获取top10活跃session
         * 1、获取到符合筛选条件的session明细数据
         * 2、按照session粒度的数据进行聚合，获取到session对应的每个品类的点击次数
         * 3、按照品类id，分组取top10，并且获取到top10活跃session
         * 4、结果的存储
         */
        getTop10Session(sc, task.getTaskid(), top10CategroyList, sessionId2DetailRDD);










        sc.stop();
    }

    /**
     * 获取top10活跃session
     * @param sc
     * @param taskid
     * @param top10CategroyList
     * @param sessionId2DetailRDD
     */
    private static void getTop10Session(
            JavaSparkContext sc,
            final long taskid,
            List<Tuple2<CategorySortKey, String>> top10CategroyList,
            JavaPairRDD<String, Row> sessionId2DetailRDD) {

        /**
         * 第一步：将top10热门品类的id转换为RDD
         */
        // 为了操作方便，将top10CategroyList中的categoryId放到一个list中
        // 格式为：<categoryId, categoryId>
        List<Tuple2<Long, Long>> top10CategoryIdList = new ArrayList<Tuple2<Long, Long>>();
        for (Tuple2<CategorySortKey, String> category : top10CategroyList) {
            long categoryId = Long.valueOf(StringUtils.getFieldFromConcatString(category._2, "\\|", Constants.FIELD_CATEGORY_ID));
            top10CategoryIdList.add(new Tuple2<Long, Long>(categoryId, categoryId));
        }

        // 将top10CategoryIdList装换为RDD
        JavaPairRDD<Long, Long> top10CategoryIdRDD = sc.parallelizePairs(top10CategoryIdList);

        /**
         * 第二步：计算top10品类被各个session点击的次数
         */
        // 以sessionId进行分组
        JavaPairRDD<String, Iterable<Row>> groupedSessionId2DetailRDD = sessionId2DetailRDD.groupByKey();

        // 把品类id对应session和count数据生成格式为：<categoryId, "sessionId,count">
        JavaPairRDD<Long, String> categoryId2SessionCountRDD =
                groupedSessionId2DetailRDD.flatMapToPair(
                        new PairFlatMapFunction<Tuple2<String, Iterable<Row>>, Long, String>() {
            @Override
            public Iterable<Tuple2<Long, String>> call(Tuple2<String, Iterable<Row>> tup) throws Exception {
                String sessionId = tup._1;
                Iterator<Row> it = tup._2.iterator();

                // 用于存储品类对的点击次数：<key=categoryId, value=次数>
                Map<Long, Long> categoryCountMap = new HashMap<Long, Long>();

                // 计算出session对应的每个品类的点击次数
                while (it.hasNext()) {
                    Row row = it.next();
                    if (row.get(6) != null) {
                        long categoryId = row.getLong(6);
                        Long count = categoryCountMap.get(categoryId);
                        if (count == null) {
                            count = 0L;
                        }
                        count ++;
                        categoryCountMap.put(categoryId, count);
                    }
                }

                // 返回结果到一个list，格式为：<categoryId, "sessionId,count">
                List<Tuple2<Long, String>> list = new ArrayList<Tuple2<Long, String>>();
                for (Map.Entry<Long, Long> categoryCountEntry : categoryCountMap.entrySet()) {
                    long categoryId = categoryCountEntry.getKey();
                    long count = categoryCountEntry.getValue();
                    String value = sessionId + "," + count;

                    list.add(new Tuple2<Long, String>(categoryId, value));
                }

                return list;
            }
        });

        // 获取到top10热门品类被各个session点击的次数,格式为：<categoryId, "sessionId,count">
        JavaPairRDD<Long, String> top10CategorySessionCountRDD =
                top10CategoryIdRDD.join(categoryId2SessionCountRDD).mapToPair(
                        new PairFunction<Tuple2<Long, Tuple2<Long, String>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<Long, String>> tup) throws Exception {

                return new Tuple2<Long, String>(tup._1, tup._2._2);
            }
        });

        /**
         * 第三步：分组取topn算法，实现获取每个品类中的top10活跃用户
         */
        // 以categoryId进行分组
        JavaPairRDD<Long, Iterable<String>> groupedTop10CategorySessionCountRDD =
                top10CategorySessionCountRDD.groupByKey();

        // 取各个品类中的前10session，返回类型：<sessionId, sessionId>
        JavaPairRDD<String, String> top10SessionRDD =
                groupedTop10CategorySessionCountRDD.flatMapToPair(
                        new PairFlatMapFunction<Tuple2<Long, Iterable<String>>, String, String>() {
            @Override
            public Iterable<Tuple2<String, String>> call(Tuple2<Long, Iterable<String>> tup) throws Exception {
                long categoryId = tup._1;
                Iterator<String> it = tup._2.iterator(); // sessionId，count

                // 声明一个数组，用来存储topn的排序数组(存储品类中的前10个session)
                String[] top10Sessions = new String[10];

                while (it.hasNext()) {
                    // "session,count"
                    String sessionCount = it.next();

                    long count = Long.valueOf(sessionCount.split(",")[1]);

                    // 遍历排序数组（topn算法）
                    for (int i = 0; i < top10Sessions.length; i++) {
                        // 判断，如果当前索引下的数据为空，就直接将sessionCount赋值给当前的i位数据
                        if (top10Sessions[i] == null) {
                            top10Sessions[i] = sessionCount;
                            break;
                        } else {
                            long _count = Long.valueOf(top10Sessions[i].split(",")[1]);

                            // 判断，如果sessionCount比i位的sessionCount（_count）大，
                            // 从排序数组最后一位开始，到i位，所有的数据往后挪一位
                            if (count > _count) {
                                for (int j = 9; j > i; j--) {
                                    top10Sessions[j] = top10Sessions[j-1];
                                }
                                // 将sessionCount赋值给top10Sessions的i位数据
                                top10Sessions[i] = sessionCount;
                                break;
                            }
                        }
                    }

                }

                // 用于存储top10Session里的sessionId,格式为：<sessionId, sessionId>
                List<Tuple2<String, String>> list = new ArrayList<Tuple2<String, String>>();

                // 将数据写入数据库
                for (String sessionCount : top10Sessions) {
                    if (sessionCount != null) {
                        String sessionId = sessionCount.split(",")[0];
                        long count = Long.valueOf(sessionCount.split(",")[1]);

                        Top10Session top10Session = new Top10Session();
                        top10Session.setTaskid(taskid);
                        top10Session.setCategoryid(categoryId);
                        top10Session.setSessionid(sessionId);
                        top10Session.setClickCount(count);

                        ITop10SessionDAO top10SessionDAO = DAOFactory.getTop10SessionDAO();
                        top10SessionDAO.insert(top10Session);

                        list.add(new Tuple2<String, String>(sessionId, sessionId));
                    }
                }

                return list;
            }
        });


        /**
         * 第四步：获取top10活跃session的明细并持久化
         */
        JavaPairRDD<String, Tuple2<String, Row>> sessionDetailRDD =
                top10SessionRDD.join(sessionId2DetailRDD);

        sessionDetailRDD.foreach(new VoidFunction<Tuple2<String, Tuple2<String, Row>>>() {
            @Override
            public void call(Tuple2<String, Tuple2<String, Row>> tup) throws Exception {
                // 获取session对应的明细数据
                Row row = tup._2._2;

                SessionDetail sessionDetail = new SessionDetail();
                sessionDetail.setTaskid(taskid);
                sessionDetail.setUserid(row.getLong(1));
                sessionDetail.setSessionid(row.getString(2));
                sessionDetail.setPageid(row.getLong(3));
                sessionDetail.setActionTime(row.getString(4));
                sessionDetail.setSearchKeyword(row.getString(5));
                sessionDetail.setClickCategoryId(row.getLong(6));
                sessionDetail.setClickProductId(row.getLong(7));
                sessionDetail.setOrderCategoryIds(row.getString(8));
                sessionDetail.setOrderProductIds(row.getString(9));
                sessionDetail.setPayCategoryIds(row.getString(10));
                sessionDetail.setPayProductIds(row.getString(11));

                ISessionDetailDAO sessionDetailDAO = DAOFactory.getSessionDetailDAO();
                sessionDetailDAO.insert(sessionDetail);
            }
        });

    }

    /**
     * 计算top10热门品类
     * @param taskid
     * @param sessionId2DetailRDD
     * @return
     */
    private static List<Tuple2<CategorySortKey, String>> getTop10Category(
            long taskid,
            JavaPairRDD<String, Row> sessionId2DetailRDD) {
        /**
         * 第一步：获取符合条件的session访问的所有品类
         */
        // 获取session访问的所有品类id（访问过指的是点击过、下单过、支付过）
        JavaPairRDD<Long, Long> categoryIdRDD = sessionId2DetailRDD.flatMapToPair(
                new PairFlatMapFunction<Tuple2<String, Row>, Long, Long>() {
            @Override
            public Iterable<Tuple2<Long, Long>> call(Tuple2<String, Row> tup) throws Exception {
                Row row = tup._2; // sessionId对应的行为数据

                // 用于存储点击、下单、支付品类信息
                List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();

                // 添加点击品类信息
                Long clickCategoryId = row.getLong(6);
                if (clickCategoryId != null){
                    list.add(new Tuple2<Long, Long>(clickCategoryId, clickCategoryId));
                }

                // 添加下单信息
                String orderCategoryIds = row.getString(8);
                if (orderCategoryIds != null) {
                    String[] orderCategoryIdsSplited = orderCategoryIds.split(",");
                    for (String orderCategoryId : orderCategoryIdsSplited) {
                        Long longOrderCategoryId = Long.valueOf(orderCategoryId);
                        list.add(new Tuple2<Long, Long>(longOrderCategoryId, longOrderCategoryId));
                    }
                }

                // 添加支付信息
                String payCategoryIds = row.getString(10);
                if (payCategoryIds != null) {
                    String[] payCategoryIdsSplited = payCategoryIds.split(",");
                    for (String payCategoryId : payCategoryIdsSplited) {
                        Long longPayCategoryId = Long.valueOf(payCategoryId);
                        list.add(new Tuple2<Long, Long>(longPayCategoryId, longPayCategoryId));
                    }
                }

                return list;
            }
        });

        /**
         * session访问过的所有品类中，可能有重复的categoryId，需要去重
         */
        categoryIdRDD = categoryIdRDD.distinct();

        /**
         * 第二步：计算各品类点击、下单、支付次数
         */
        // 计算各品类的点击次数
        JavaPairRDD<Long, Long> clickCategoryId2CountRDD =
                getClickCategoryId2CountRDD(sessionId2DetailRDD);

        // 计算各品类的下单次数
        JavaPairRDD<Long, Long> orderCategoryId2CountRDD =
                getOrderCategoryId2CountRDD(sessionId2DetailRDD);

        // 计算各品类的支付次数
        JavaPairRDD<Long, Long> payCategoryId2CountRDD =
                getPayCategoryId2CountRDD(sessionId2DetailRDD);

        /**
         * 第三步：join各品类和他的点击、下单、支付次数
         * categoryIdRDD数据里面，包含了所有符合条件的过滤掉重复品类的session
         * 在第二步中分别计算了点击下单支付次数，可能不是包含所有品类的
         * 比如：有的品类只是点击过，但没有下单，类似的这种情况有很多
         * 所以，在这里如果要做join，就不能用join，需要用leftOuterJoin
         */
        JavaPairRDD<Long, String> categoryId2CountRDD = joinCategoryAndDetail(
                categoryIdRDD,
                clickCategoryId2CountRDD,
                orderCategoryId2CountRDD,
                payCategoryId2CountRDD
        );

        /**
         * 第四步：实现自定义排序类
         */

        /**
         * 第五步：将数据映射为：<CategorySortKey, countInfo>, 再进行二次排序
         */
        JavaPairRDD<CategorySortKey, String> sortKeyCountRDD = categoryId2CountRDD.mapToPair(
                new PairFunction<Tuple2<Long, String>, CategorySortKey, String>() {
            @Override
            public Tuple2<CategorySortKey, String> call(Tuple2<Long, String> tup) throws Exception {
                String countInfo = tup._2;

                long clickCount = Long.valueOf(StringUtils.getFieldFromConcatString(countInfo, "\\|", Constants.FIELD_CLICK_COUNT));
                long orderCount = Long.valueOf(StringUtils.getFieldFromConcatString(countInfo, "\\|", Constants.FIELD_ORDER_COUNT));
                long payCount = Long.valueOf(StringUtils.getFieldFromConcatString(countInfo, "\\|", Constants.FIELD_PAY_COUNT));

                // 创建自定义排序实例
                CategorySortKey sortKey = new CategorySortKey(clickCount, orderCount, payCount);

                return new Tuple2<CategorySortKey, String>(sortKey, countInfo);
            }
        });

        // 进行降序排序
        JavaPairRDD<CategorySortKey, String> sortedCategoryCountRDD =
                sortKeyCountRDD.sortByKey(false);


        /**
         * 第六步: 取出top10热门品类并持久化到数据库
         */
        List<Tuple2<CategorySortKey, String>> top10CategoryList = sortedCategoryCountRDD.take(10);

        ITop10CategoryDAO top10CategoryDAO = DAOFactory.getTop10CategoryDAO();
        for (Tuple2<CategorySortKey, String> tuple : top10CategoryList) {
            String countInfo = tuple._2;
            long categoryId = Long.valueOf(StringUtils.getFieldFromConcatString(countInfo, "\\|", Constants.FIELD_CATEGORY_ID));
            long clickCount = Long.valueOf(StringUtils.getFieldFromConcatString(countInfo, "\\|", Constants.FIELD_CLICK_COUNT));
            long orderCount = Long.valueOf(StringUtils.getFieldFromConcatString(countInfo, "\\|", Constants.FIELD_ORDER_COUNT));
            long payCount = Long.valueOf(StringUtils.getFieldFromConcatString(countInfo, "\\|", Constants.FIELD_PAY_COUNT));

            Top10Category top10Category = new Top10Category();
            top10Category.setTaskid(taskid);
            top10Category.setCategoryid(categoryId);
            top10Category.setClickCount(clickCount);
            top10Category.setOrderCount(orderCount);
            top10Category.setPayCount(payCount);

            top10CategoryDAO.insert(top10Category);
        }

        return top10CategoryList;
    }

    /**
     * 连接品类RDD和数据RDD
     * @param categoryIdRDD
     * @param clickCategoryId2CountRDD
     * @param orderCategoryId2CountRDD
     * @param payCategoryId2CountRDD
     * @return
     */
    private static JavaPairRDD<Long, String> joinCategoryAndDetail(
            JavaPairRDD<Long, Long> categoryIdRDD,
            JavaPairRDD<Long, Long> clickCategoryId2CountRDD,
            JavaPairRDD<Long, Long> orderCategoryId2CountRDD,
            JavaPairRDD<Long, Long> payCategoryId2CountRDD) {
        // 注意：如果用leftOuterJoin，就可能出现右边RDD中join过来的值为空的情况
        // 所有tuple中的第二个值用Optional<Long>类型，代表可能有值，也可能没有值
        JavaPairRDD<Long, Tuple2<Long, com.google.common.base.Optional<Long>>> tmpJoinRDD =
                categoryIdRDD.leftOuterJoin(clickCategoryId2CountRDD);

        // 把数据生成格式为: (categoryId, "categoryId=品类|clickCount=点击次数")
        JavaPairRDD<Long, String> tmpMapRDD = tmpJoinRDD.mapToPair(
                new PairFunction<Tuple2<Long, Tuple2<Long, com.google.common.base.Optional<Long>>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<Long, com.google.common.base.Optional<Long>>> tup) throws Exception {

                long categoryId = tup._1;
                com.google.common.base.Optional<Long> optional = tup._2._2;

                long clickCount = 0L;

                if (optional.isPresent()) {
                    clickCount = optional.get();
                }

                String value = Constants.FIELD_CATEGORY_ID + "=" + categoryId + "|" +
                        Constants.FIELD_CLICK_COUNT + "=" + clickCount;

                return new Tuple2<Long, String>(categoryId, value);
            }
        });

        // 再次与下单次数进行leftOuterJoin
        tmpMapRDD = tmpMapRDD.leftOuterJoin(orderCategoryId2CountRDD).mapToPair(
                new PairFunction<Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>> tup) throws Exception {
                long categoryId = tup._1;
                String value = tup._2._1;
                com.google.common.base.Optional<Long> optional = tup._2._2;

                long orderCount = 0L;

                if (optional.isPresent()) {
                    orderCount = optional.get();
                }

                value = value + "|" + Constants.FIELD_ORDER_COUNT + "=" + orderCount;

                return new Tuple2<Long, String>(categoryId, value);
            }
        });

        // 再与支付次数进行leftOuterJoin
        tmpMapRDD = tmpMapRDD.leftOuterJoin(payCategoryId2CountRDD).mapToPair(
                new PairFunction<Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>>, Long, String>() {
            @Override
            public Tuple2<Long, String> call(Tuple2<Long, Tuple2<String, com.google.common.base.Optional<Long>>> tup) throws Exception {
                long categoryId = tup._1;
                String value = tup._2._1;
                com.google.common.base.Optional<Long> optional = tup._2._2;

                long payCount = 0L;

                if (optional.isPresent()) {
                    payCount = optional.get();
                }

                value = value + "|" + Constants.FIELD_PAY_COUNT + "=" + payCount;

                return new Tuple2<Long, String>(categoryId, value);
            }
        });

        return tmpMapRDD;
    }

    /**
     * 计算各品类的支付次数
     * @param sessionId2DetailRDD
     * @return
     */
    private static JavaPairRDD<Long, Long> getPayCategoryId2CountRDD(
            JavaPairRDD<String, Row> sessionId2DetailRDD) {

        // 过滤支付字段值为空的数据
        JavaPairRDD<String, Row> payActionRDD = sessionId2DetailRDD.filter(new Function<Tuple2<String, Row>, Boolean>() {
            @Override
            public Boolean call(Tuple2<String, Row> tup) throws Exception {
                Row row = tup._2;
                return row.getString(10) != null ? true : false;
            }
        });

        // 生成元组，便于聚合
        JavaPairRDD<Long, Long> payCategoryIdRDD = payActionRDD.flatMapToPair(
                new PairFlatMapFunction<Tuple2<String, Row>, Long, Long>() {
                    @Override
                    public Iterable<Tuple2<Long, Long>> call(Tuple2<String, Row> tup) throws Exception {
                        Row row = tup._2;
                        String payCategoryIds = row.getString(10);
                        String[] payCategoryIdsSplited = payCategoryIds.split(",");

                        // 用于存储切分后的数据: (orderCategoryId, 1L)
                        List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();
                        for (String payCategoryId : payCategoryIdsSplited) {
                            list.add(new Tuple2<Long, Long>(Long.valueOf(payCategoryId), 1L));
                        }

                        return list;
                    }
                });

        // 聚合
        JavaPairRDD<Long, Long> payCategoryId2CountRDD =
                payCategoryIdRDD.reduceByKey(new Function2<Long, Long, Long>() {
                    @Override
                    public Long call(Long v1, Long v2) throws Exception {
                        return v1 + v2;
                    }
                });

        return payCategoryId2CountRDD;
    }


    /**
     * 计算各品类的下单次数
     * @param sessionId2DetailRDD
     * @return
     */
    private static JavaPairRDD<Long, Long> getOrderCategoryId2CountRDD(
            JavaPairRDD<String, Row> sessionId2DetailRDD) {

        // 过滤下单字段值为空的数据
        JavaPairRDD<String, Row> orderActionRDD = sessionId2DetailRDD.filter(new Function<Tuple2<String, Row>, Boolean>() {
            @Override
            public Boolean call(Tuple2<String, Row> tup) throws Exception {
                Row row = tup._2;
                return row.getString(8) != null ? true : false;
            }
        });

        // 生成元组，便于聚合
        JavaPairRDD<Long, Long> orderCategoryIdRDD = orderActionRDD.flatMapToPair(
                new PairFlatMapFunction<Tuple2<String, Row>, Long, Long>() {
            @Override
            public Iterable<Tuple2<Long, Long>> call(Tuple2<String, Row> tup) throws Exception {
                Row row = tup._2;
                String orderCategoryIds = row.getString(8);
                String[] orderCategoryIdsSplited = orderCategoryIds.split(",");

                // 用于存储切分后的数据: (orderCategoryId, 1L)
                List<Tuple2<Long, Long>> list = new ArrayList<Tuple2<Long, Long>>();
                for (String orderCategoryId : orderCategoryIdsSplited) {
                    list.add(new Tuple2<Long, Long>(Long.valueOf(orderCategoryId), 1L));
                }

                return list;
            }
        });

        // 聚合
        JavaPairRDD<Long, Long> orderCategoryId2CountRDD =
                orderCategoryIdRDD.reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long v1, Long v2) throws Exception {
                return v1 + v2;
            }
        });

        return orderCategoryId2CountRDD;
    }

    /**
     * 算各品类的点击次数
     * @param sessionId2DetailRDD
     * @return
     */
    private static JavaPairRDD<Long, Long> getClickCategoryId2CountRDD(
            JavaPairRDD<String, Row> sessionId2DetailRDD) {
        // 把明细数据中的点击品类字段的空字段过滤掉
        JavaPairRDD<String, Row> clickActionRDD = sessionId2DetailRDD.filter(new Function<Tuple2<String, Row>, Boolean>() {
            @Override
            public Boolean call(Tuple2<String, Row> tup) throws Exception {
                Row row = tup._2;
                return row.get(6) != null ? true : false;
            }
        });

        // 将每一个点击品类后面跟一个1，生成元组: (clickCategoryId, 1), 为了做聚合
        JavaPairRDD<Long, Long> clickCategoryIdRDD = clickActionRDD.mapToPair(
                new PairFunction<Tuple2<String, Row>, Long, Long>() {
            @Override
            public Tuple2<Long, Long> call(Tuple2<String, Row> tup) throws Exception {
                long clickCategoryId = tup._2.getLong(6);
                return new Tuple2<Long, Long>(clickCategoryId, 1L);
            }
        });

        // 计算各个品类的点击次数
        JavaPairRDD<Long, Long> clickCagegoryId2CountRDD =
                clickCategoryIdRDD.reduceByKey(new Function2<Long, Long, Long>() {
            @Override
            public Long call(Long v1, Long v2) throws Exception {
                return v1 + v2;
            }
        });

        return clickCagegoryId2CountRDD;
    }

    /**
     * 按照时间比例随机抽取session
     * @param sc
     * @param taskid
     * @param filteredSessionId2AggrInfoRDD
     * @param sessionId2DetailRDD
     */
    private static void randomExtranctSession(
            JavaSparkContext sc,
            final long taskid,
            JavaPairRDD<String, String> filteredSessionId2AggrInfoRDD,
            JavaPairRDD<String, Row> sessionId2DetailRDD) {
        /**
         * 第一步：计算出每个小时的session数量
         */
        // 首先需要把数据调整为: <date_hour, data>
        JavaPairRDD<String, String> time2SessionIdRDD =
                filteredSessionId2AggrInfoRDD.mapToPair(
                        new PairFunction<Tuple2<String, String>, String, String>() {
            @Override
            public Tuple2<String, String> call(Tuple2<String, String> tup) throws Exception {
                // 获取聚合数据
                String aggrInfo = tup._2;
                // 再从聚合数据里拿到startTime
                String startTime = StringUtils.getFieldFromConcatString(
                        aggrInfo, "\\|", Constants.FIELD_START_TIME);

                // 获取日期和时间(date_hour)
                String dateHour = DateUtils.getDateHour(startTime);

                return new Tuple2<String, String>(dateHour, aggrInfo);
            }
        });

        // 需要得到每天每小时的session数量，然后计算出每天每小时session抽取索引，遍历每天每小时的session
        // 首先抽取出session聚合数据，写入数据库表：session_random_extract
        // time2SessionIdRDD的数据,是每天的某个小时的session聚合数据

        // 计算每天每小时的session数量
        Map<String, Object> countMap = time2SessionIdRDD.countByKey();

        /**
         * 第二步：使用时间比例随机抽取算法，计算出每天每小时抽取的session索引
         */
        // 将countMap<yyyy-MM-dd_HH, count>转换为<yyyy-MM-dd, <HH, count>>放到Map里
        HashMap<String, Map<String, Long>> dateHourCountMap = new HashMap<String, Map<String, Long>>();

        // 调用循环把数据放到dateHourCountMap
        for (Map.Entry<String, Object> countEntry : countMap.entrySet()) {
            // 取出日期和时间
            String dateHour = countEntry.getKey();
            String date = dateHour.split("_")[0];
            String hour = dateHour.split("_")[1];

            // 取出每小时的count数
            long count = Long.valueOf(String.valueOf(countEntry.getValue()));

            // 用来存储<hour, count>
            Map<String, Long> hourCountMap = dateHourCountMap.get(date);
            if (hourCountMap == null) {
                hourCountMap = new HashMap<String, Long>();
                dateHourCountMap.put(date, hourCountMap);
            }
            hourCountMap.put(hour, count);
        }

        // 实现按时间比例抽取算法
        // 现在要从数据中抽取100个session，先按照天数进行平分
        int extractNumber = 100 / dateHourCountMap.size();

        // Map<date, Map<hour, List(2, 13, 4 ,7....)>>
        Map<String, Map<String, List<Integer>>> dateHourExtractMap = new HashMap<String, Map<String, List<Integer>>>();

        Random random = new Random();

        for (Map.Entry<String, Map<String, Long>> dateHourCountEntry : dateHourCountMap.entrySet()) {
            String date = dateHourCountEntry.getKey(); // 日期
            Map<String, Long> hourCountMap = dateHourCountEntry.getValue(); // 小时和对应的count数

            // 计算出当前这一天的session总数
            Long sessionCount = 0L;
            for (long hourCount : hourCountMap.values()) {
                sessionCount += hourCount;
            }

            // 把一天的session数量put到dateHourExtractmap
            Map<String, List<Integer>> hourExtractMap = dateHourExtractMap.get(date);
            if (hourExtractMap == null) {
                hourExtractMap = new HashMap<String, List<Integer>>();
                dateHourExtractMap.put(date, hourExtractMap);
            }

            // 遍历每个小时, 获取每个小时的session数量
                    for (Map.Entry<String, Long> hourCountEntry : hourCountMap.entrySet()) {
                        String hour = hourCountEntry.getKey(); // 小时
                        long count = hourCountEntry.getValue(); // 小时对应的count数

                        // 计算每个小时session数量占当天session数量的占比，乘以要抽取的数量
                        // 最后计算出当前小时需要抽取的session数量
                        int hourExtractNumber = (int)(((double)count / (double)sessionCount) * extractNumber);

                        // 当前要抽取的session数量有可能大于每小时session数量
                        // 让当前小时需要抽取的session数量直接等于每小时session数量
                        if (hourExtractNumber > count) {
                            hourExtractNumber = (int)count;
                }

                // 获取当前小时的存放随机数的list, 如果没有就创建一个
                List<Integer> extractIndexList = hourExtractMap.get(hour);
                if (extractIndexList == null) {
                    extractIndexList = new ArrayList<Integer>();
                    hourExtractMap.put(hour, extractIndexList);
                }

                // 生成上面计算出来的随机数,用while判断生成的随机数不能重复
                for (int i = 0; i < hourExtractNumber; i++) {
                    int extractIndex = random.nextInt((int)count); // 生成随机索引
                    while (extractIndexList.contains(extractIndex)) {
                        // 如果有重复的随机索引，就重新生成随机数
                        extractIndex = random.nextInt((int)count);
                    }
                    extractIndexList.add(extractIndex);
                }
            }
        }

        // 把dateHourExtractMap封装到fastUtilDateHourExtractMap
        // fastUtil可以封装Map、List、Set，相比较普通的Map、List、Set占用的内存更小，
        // 所有在分布式计算的过程中传输的速度更快，占用的网络带宽更小
        Map<String, Map<String, IntList>> fastUtilDateHourExtractMap = new HashMap<String, Map<String, IntList>>();

        for (Map.Entry<String, Map<String, List<Integer>>> dateHourExtractEntry : dateHourExtractMap.entrySet()) {
            // date
            String date = dateHourExtractEntry.getKey();
            // <hour, extract>
            Map<String, List<Integer>> hourExtractMap = dateHourExtractEntry.getValue();

            // 用于存放<hour, extract>
            Map<String, IntList> fastUtilHourExtractMap = new HashMap<String, IntList>();
            for (Map.Entry<String, List<Integer>> hourExtractEntry : hourExtractMap.entrySet()) {
                // hour
                String hour = hourExtractEntry.getKey();
                // extract
                List<Integer> extractList = hourExtractEntry.getValue();

                // 封装
                IntList fastUtilExtractList = new IntArrayList();
                for (int i = 0; i < extractList.size(); i++) {
                    fastUtilExtractList.add(extractList.get(i));
                }

                fastUtilHourExtractMap.put(hour, fastUtilExtractList);
            }

            fastUtilDateHourExtractMap.put(date, fastUtilHourExtractMap);
        }

        /**
         * 在集群执行task时，有可能多个Executor会远程的获取上面的Map值
         * 这样会产生大量的网络IO，此时最好用广播变量把该值广播到每一个参与计算的Executor
         * 可以提高运行效率
         */
        final Broadcast<Map<String, Map<String, IntList>>> dateHourExtractMapBroadcast =
                sc.broadcast(fastUtilDateHourExtractMap);

        /**
         * 第三步：遍历每天每小时的session，根据随机抽取索引开始抽取
         */
        // 需要获取到<dateHour, <session, aggrInfo>>
        JavaPairRDD<String, Iterable<String>> time2SessionRDD = time2SessionIdRDD.groupByKey();

        // 遍历每天每小时的session
        // 如果发现某个session正好在指定的这天这个小时的随机抽取索引上
        // 将该session写入到数据库表：session_random_extract
        // 然后再将抽取出来的session返回，生成新的JavaRDD<String>
        // 用抽取出来的sessionId，去join他们的访问明细，写入数据库表：session_detail
        JavaPairRDD<String, String> extractSessionIdsRDD =
                time2SessionRDD.flatMapToPair(
                        new PairFlatMapFunction<Tuple2<String, Iterable<String>>, String, String>() {
            @Override
            public Iterable<Tuple2<String, String>> call(Tuple2<String, Iterable<String>> tup) throws Exception {
                // 用来存储<sessionId, sessionId>
                List<Tuple2<String, String>> extractSessionIds = new ArrayList<Tuple2<String, String>>();

                String dateHour = tup._1;
                String date = dateHour.split("_")[0];
                String hour = dateHour.split("_")[1];

                Iterator<String> it = tup._2.iterator();

                // 调用广播过来的值
                Map<String, Map<String, IntList>> dateHourExtractMap = dateHourExtractMapBroadcast.value();

                // 获取抽取索引List
                IntList extractIndexList = dateHourExtractMap.get(date).get(hour);

                ISessionRandomExtractDAO sessionRandomExtractDAO = DAOFactory.getSessionRandomExtractDAO();

                int index = 0;
                while (it.hasNext()) {
                    String sessionAggrInfo = it.next();
                    if (extractIndexList.contains(index)) {
                        String sessionId = StringUtils.getFieldFromConcatString(
                                sessionAggrInfo, "\\|", Constants.FIELD_SESSION_ID);

                        // 将数据存入数据库
                        SessionRandomExtract sessionRandomExtract = new SessionRandomExtract();
                        sessionRandomExtract.setTaskid(taskid);
                        sessionRandomExtract.setSessionid(sessionId);
                        sessionRandomExtract.setStartTime(StringUtils.getFieldFromConcatString(sessionAggrInfo, "\\|", Constants.FIELD_START_TIME));
                        sessionRandomExtract.setSearchKeywords(StringUtils.getFieldFromConcatString(sessionAggrInfo, "\\|", Constants.FIELD_SEARCH_KEYWORDS));
                        sessionRandomExtract.setClickCategoryIds(StringUtils.getFieldFromConcatString(sessionAggrInfo, "\\|", Constants.FIELD_CLICK_CATEGORY_IDS));

                        sessionRandomExtractDAO.insert(sessionRandomExtract);

                        // 将sessionId放入list
                        extractSessionIds.add(new Tuple2<String, String>(sessionId, sessionId));
                    }

                    index ++;
                }

                return extractSessionIds;
            }
        });

        /**
         * 第四步：获取抽取出来的session对应的明细并存入数据库
         */
        // 把明细join进来
        JavaPairRDD<String, Tuple2<String, Row>> extractSessionDetailRDD =
                extractSessionIdsRDD.join(sessionId2DetailRDD);

        extractSessionDetailRDD.foreachPartition(new VoidFunction<Iterator<Tuple2<String, Tuple2<String, Row>>>>() {
            @Override
            public void call(Iterator<Tuple2<String, Tuple2<String, Row>>> it) throws Exception {
                // 用来存储明细数据的List
                List<SessionDetail> sessionDetails = new ArrayList<SessionDetail>();

                while (it.hasNext()) {
                    Tuple2<String, Tuple2<String, Row>> tuple = it.next();
                    Row row = tuple._2._2;
                    SessionDetail sessionDetail = new SessionDetail();
                    sessionDetail.setTaskid(taskid);
                    sessionDetail.setUserid(row.getLong(1));
                    sessionDetail.setSessionid(row.getString(2));
                    sessionDetail.setPageid(row.getLong(3));
                    sessionDetail.setActionTime(row.getString(4));
                    sessionDetail.setSearchKeyword(row.getString(5));
                    sessionDetail.setClickCategoryId(row.getLong(6));
                    sessionDetail.setClickProductId(row.getLong(7));
                    sessionDetail.setOrderCategoryIds(row.getString(8));
                    sessionDetail.setOrderProductIds(row.getString(9));
                    sessionDetail.setPayCategoryIds(row.getString(10));
                    sessionDetail.setPayProductIds(row.getString(11));

                    sessionDetails.add(sessionDetail);
                }

                ISessionDetailDAO sessionDetailDAO = DAOFactory.getSessionDetailDAO();
                sessionDetailDAO.insertBatch(sessionDetails);
            }
        });


    }

    /**
     * 计算出各个范围的session占比，并写入数据库
     * @param value
     * @param taskid
     */
    private static void calculateAndPersistAggrStat(String value, long taskid) {
        // 首先从Accumulator统计的字符串结果中获取各个值
        long session_count = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.SESSION_COUNT));
        long visit_length_1s_3s = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_1s_3s));
        long visit_length_4s_6s = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_4s_6s));
        long visit_length_7s_9s = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_7s_9s));
        long visit_length_10s_30s = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_10s_30s));
        long visit_length_30s_60s = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_30s_60s));
        long visit_length_1m_3m = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_1m_3m));
        long visit_length_3m_10m = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_3m_10m));
        long visit_length_10m_30m = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_10m_30m));
        long visit_length_30m = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.TIME_PERIOD_30m));

        long step_length_1_3 = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.STEP_PERIOD_1_3));
        long step_length_4_6 = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.STEP_PERIOD_4_6));
        long step_length_7_9 = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.STEP_PERIOD_7_9));
        long step_length_10_30 = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.STEP_PERIOD_10_30));
        long step_length_30_60 = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.STEP_PERIOD_30_60));
        long step_length_60 = Long.valueOf(StringUtils.getFieldFromConcatString(value, "\\|", Constants.STEP_PERIOD_60));

        // 计算各个访问时长和访问步长的范围占比
        double visit_length_1s_3s_ratio = NumberUtils.formatDouble((double)visit_length_1s_3s / (double) session_count, 2);
        double visit_length_4s_6s_ratio = NumberUtils.formatDouble((double)visit_length_4s_6s / (double) session_count, 2);
        double visit_length_7s_9s_ratio = NumberUtils.formatDouble((double)visit_length_7s_9s / (double) session_count, 2);
        double visit_length_10s_30s_ratio = NumberUtils.formatDouble((double)visit_length_10s_30s / (double) session_count, 2);
        double visit_length_30s_60s_ratio = NumberUtils.formatDouble((double)visit_length_30s_60s / (double) session_count, 2);
        double visit_length_1m_3m_ratio = NumberUtils.formatDouble((double)visit_length_1m_3m / (double) session_count, 2);
        double visit_length_3m_10m_ratio = NumberUtils.formatDouble((double)visit_length_3m_10m / (double) session_count, 2);
        double visit_length_10m_30m_ratio = NumberUtils.formatDouble((double)visit_length_10m_30m / (double) session_count, 2);
        double visit_length_30m_ratio = NumberUtils.formatDouble((double)visit_length_30m / (double) session_count, 2);

        double step_length_1_3_ratio = NumberUtils.formatDouble((double)step_length_1_3 / (double) session_count, 2);
        double step_length_4_6_ratio = NumberUtils.formatDouble((double)step_length_4_6 / (double) session_count, 2);
        double step_length_7_9_ratio = NumberUtils.formatDouble((double)step_length_7_9 / (double) session_count, 2);
        double step_length_10_30_ratio = NumberUtils.formatDouble((double)step_length_10_30 / (double) session_count, 2);
        double step_length_30_60_ratio = NumberUtils.formatDouble((double)step_length_30_60 / (double) session_count, 2);
        double step_length_60_ratio = NumberUtils.formatDouble((double)step_length_60 / (double) session_count, 2);

        // 将统计结果封装到Domain对象里
        SessionAggrStat sessionAggrStat = new SessionAggrStat();
        sessionAggrStat.setTaskid(taskid);
        sessionAggrStat.setSession_count(session_count);
        sessionAggrStat.setVisit_length_1s_3s_ratio(visit_length_1s_3s_ratio);
        sessionAggrStat.setVisit_length_4s_6s_ratio(visit_length_4s_6s_ratio);
        sessionAggrStat.setVisit_length_7s_9s_ratio(visit_length_7s_9s_ratio);
        sessionAggrStat.setVisit_length_10s_30s_ratio(visit_length_10s_30s_ratio);
        sessionAggrStat.setVisit_length_30s_60s_ratio(visit_length_30s_60s_ratio);
        sessionAggrStat.setVisit_length_1m_3m_ratio(visit_length_1m_3m_ratio);
        sessionAggrStat.setVisit_length_3m_10m_ratio(visit_length_3m_10m_ratio);
        sessionAggrStat.setVisit_length_10m_30m_ratio(visit_length_10m_30m_ratio);
        sessionAggrStat.setVisit_length_30m_ratio(visit_length_30m_ratio);
        sessionAggrStat.setStep_length_1_3_ratio(step_length_1_3_ratio);
        sessionAggrStat.setStep_length_4_6_ratio(step_length_4_6_ratio);
        sessionAggrStat.setStep_length_7_9_ratio(step_length_7_9_ratio);
        sessionAggrStat.setStep_length_10_30_ratio(step_length_10_30_ratio);
        sessionAggrStat.setStep_length_30_60_ratio(step_length_30_60_ratio);
        sessionAggrStat.setStep_length_60_ratio(step_length_60_ratio);

        // 结果存储
        ISessionAggrStatDAO sessionAggrStatDAO = DAOFactory.getSessionAggrStatDAO();
        sessionAggrStatDAO.insert(sessionAggrStat);
    }

    /**
     * 获取通过筛选条件的session的访问明细数据
     * @param filteredSessionId2AggrInfoRDD
     * @param sessionId2ActionRDD
     * @return
     */
    private static JavaPairRDD<String, Row> getSessionId2DetailRDD(
            JavaPairRDD<String, String> filteredSessionId2AggrInfoRDD,
            JavaPairRDD<String, Row> sessionId2ActionRDD) {

        // 得到sessionId对应的按照使用者条件过滤后的明细数据
        JavaPairRDD<String, Row> sessionId2DetailRDD =
                filteredSessionId2AggrInfoRDD.join(sessionId2ActionRDD)
                        .mapToPair(new PairFunction<Tuple2<String, Tuple2<String, Row>>, String, Row>() {
            @Override
            public Tuple2<String, Row> call(Tuple2<String, Tuple2<String, Row>> tup) throws Exception {

                return new Tuple2<String, Row>(tup._1, tup._2._2);
            }
        });


        return sessionId2DetailRDD;
    }

    /**
     * 按照使用者条件过滤session数据并进行聚合
     *
     * @param sessionId2AggrInfoRDD
     * @param taskParam
     * @param sessionAggrStatAccumulator
     * @return
     */
    private static JavaPairRDD<String, String> filteredSessionAndAggrStat(
            JavaPairRDD<String, String> sessionId2AggrInfoRDD,
            JSONObject taskParam,
            final Accumulator<String> sessionAggrStatAccumulator) {

        // 先把所有筛选条件提取出来并拼接为一条字符串
        String startAge = ParamUtils.getParam(taskParam, Constants.PARAM_START_AGE);
        String endAge = ParamUtils.getParam(taskParam, Constants.PARAM_END_AGE);
        String professionals = ParamUtils.getParam(taskParam, Constants.PARAM_PROFESSIONALS);
        String cities = ParamUtils.getParam(taskParam, Constants.PARAM_CITIES);
        String sex = ParamUtils.getParam(taskParam, Constants.PARAM_SEX);
        String keywords = ParamUtils.getParam(taskParam, Constants.PARAM_KEYWORDS);
        String categorys = ParamUtils.getParam(taskParam, Constants.PARAM_CATEGORY_IDS);

        String _parameter = (startAge != null ? Constants.PARAM_START_AGE + "=" + startAge + "|" : "")
                + (endAge != null ? Constants.PARAM_END_AGE + "=" + endAge + "|" : "")
                + (professionals != null ? Constants.PARAM_PROFESSIONALS + "=" + professionals + "|" : "")
                + (cities != null ? Constants.PARAM_CITIES + "=" + cities + "|" : "")
                + (sex != null ? Constants.PARAM_SEX + "=" + sex + "|" : "")
                + (keywords != null ? Constants.PARAM_KEYWORDS + "=" + keywords + "|" : "")
                + (categorys != null ? Constants.PARAM_CATEGORY_IDS + "=" + categorys + "|" : "");

        // 把_parameter的值的最后一个“|”截取掉
        if (_parameter.endsWith("\\|")) {
            _parameter = _parameter.substring(0, _parameter.length() - 1);
        }

        final String parameter = _parameter;

        // 根据筛选条件进行过滤
        JavaPairRDD<String, String> filteredSessionAggrInfoRDD =
                sessionId2AggrInfoRDD.filter(new Function<Tuple2<String, String>, Boolean>() {
                    @Override
                    public Boolean call(Tuple2<String, String> tup) throws Exception {
                        // 从tup中获取基础数据
                        String aggrInfo = tup._2;

                        /**
                         * 依次按照筛选条件进行过滤
                         */
                        // 按照年龄进行过滤
                        if (!ValidUtils.between(aggrInfo, Constants.FIELD_AGE, parameter,
                                Constants.PARAM_START_AGE, Constants.PARAM_END_AGE)) {
                            return false;
                        }

                        // 按照职业进行过滤
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_PROFESSIONAL, parameter,
                                Constants.PARAM_PROFESSIONALS)) {
                            return false;
                        }

                        // 按照城市信息进行过滤
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_CITY, parameter,
                                Constants.PARAM_CITIES)) {
                            return false;
                        }

                        // 按照性别进行过滤
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_SEX, parameter,
                                Constants.PARAM_SEX)) {
                            return false;
                        }

                        // 按照关键字进行过滤
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_SEARCH_KEYWORDS, parameter,
                                Constants.PARAM_KEYWORDS)) {
                            return false;
                        }

                        // 按照点击品类进行过滤
                        if (!ValidUtils.in(aggrInfo, Constants.FIELD_CLICK_CATEGORY_IDS, parameter,
                                Constants.PARAM_CATEGORY_IDS)) {
                            return false;
                        }

                        /**
                         * 代码执行到这里，说明该session通过了用户指定的筛选条件
                         * 接下来要对session的访问时长和访问步长进行统计
                         */

                        // 根据session对应的时长和步长的时间范围进行累加操作
                        sessionAggrStatAccumulator.add(Constants.SESSION_COUNT);

                        // 计算出session的访问时长和访问步长的范围并进行累加
                        long visitLength = Long.valueOf(StringUtils.getFieldFromConcatString(
                                aggrInfo, "\\|", Constants.FIELD_VISIT_LENGTH));
                        long stepLength = Long.valueOf(StringUtils.getFieldFromConcatString(
                                aggrInfo, "\\|", Constants.FIELD_STEP_LENGTH));

                        // 计算访问时长范围
                        calculateVisitLength(visitLength);

                        // 计算访问步长范围
                        calculateStepLength(stepLength);

                        return true;
                    }

                    /**
                     * 计算访问步长范围
                     * @param stepLength
                     */
                    private void calculateStepLength(long stepLength) {
                        if (stepLength >= 1 && stepLength <= 3) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_1_3);
                        } else if (stepLength >= 4 && stepLength <= 6) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_4_6);
                        } else if (stepLength >= 7 && stepLength <= 9) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_7_9);
                        } else if (stepLength >= 10 && stepLength < 30) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_10_30);
                        } else if (stepLength >= 30 && stepLength < 60) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_30_60);
                        } else if (stepLength >= 60) {
                            sessionAggrStatAccumulator.add(Constants.STEP_PERIOD_60);
                        }
                    }

                    /**
                     * 计算访问时长范围
                     * @param visitLength
                     */
                    private void calculateVisitLength(long visitLength) {
                        if (visitLength >= 1 && visitLength <= 3) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1s_3s);
                        } else if (visitLength >= 4 && visitLength <= 6) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_4s_6s);
                        }  else if (visitLength >= 7 && visitLength <= 9) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_7s_9s);
                        } else if (visitLength >= 10 && visitLength < 30) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10s_30s);
                        } else if (visitLength >= 30 && visitLength < 60) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30s_60s);
                        } else if (visitLength >= 60 && visitLength < 180) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_1m_3m);
                        } else if (visitLength >= 180 && visitLength < 600) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_3m_10m);
                        } else if (visitLength >= 600 && visitLength < 1800) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_10m_30m);
                        } else if (visitLength >= 1800) {
                            sessionAggrStatAccumulator.add(Constants.TIME_PERIOD_30m);
                        }
                    }
                });

        return filteredSessionAggrInfoRDD;
    }

    /**
     * 对行为数据按照session粒度进行聚合
     *
     * @param sc
     * @param sqlContext
     * @param sessionId2ActionRDD
     * @return
     */
    private static JavaPairRDD<String, String> aggrgateBySession(
            JavaSparkContext sc,
            SQLContext sqlContext,
            JavaPairRDD<String, Row> sessionId2ActionRDD) {

        // 对行为数据进行分组
        JavaPairRDD<String, Iterable<Row>> sessionId2ActionPairRDD =
                sessionId2ActionRDD.groupByKey();

        // 对每个session分组进行聚合，将session中所有的搜索关键字和点击品类都聚合起来
        // 格式：<userId, partAggrInfo(sessionId, searchKeywords, clickCategoryIds, visitLength, stepLength, startTime)>
        JavaPairRDD<Long, String> userId2PartAggrInfoRDD = sessionId2ActionPairRDD.mapToPair(
                new PairFunction<Tuple2<String, Iterable<Row>>, Long, String>() {
                    @Override
                    public Tuple2<Long, String> call(Tuple2<String, Iterable<Row>> tup) throws Exception {
                        String sessionId = tup._1;
                        Iterator<Row> it = tup._2.iterator();

                        // 用来存储搜索关键字和点击品类
                        StringBuffer searchKeywordsBuffer = new StringBuffer();
                        StringBuffer clickCategoryIdsBuffer = new StringBuffer();

                        // 用来存储userId
                        Long userId = null;

                        // 用来存储起始时间和结束时间
                        Date startTime = null;
                        Date endTime = null;

                        // 用来存储session的访问步长
                        int stepLength = 0;

                        // 遍历session中所有的行为数据
                        while (it.hasNext()) {
                            Row row = it.next();
                            if (userId == null) {
                                userId = row.getLong(1);
                            }

                            // 获取每个访问行为的搜索关键字和点击品类
                            // 注意：如果该行为是搜索行为，searchKeyword是有值的
                            // 但同时点击行为就没有值，任何的行为，不可能有两个字段都有值
                            String searchKeyword = row.getString(5);
                            String clickCategoryId = String.valueOf(row.getLong(6));

                            // 追加搜关键字
                            if (!StringUtils.isEmpty(searchKeyword)) {
                                if (!searchKeywordsBuffer.toString().contains(searchKeyword)) {
                                    searchKeywordsBuffer.append(searchKeyword + ",");
                                }
                            }

                            // 追加点击品类
                            if (clickCategoryId != null) {
                                if (!clickCategoryIdsBuffer.toString().contains(clickCategoryId)) {
                                    clickCategoryIdsBuffer.append(clickCategoryId + ",");
                                }
                            }

                            // 计算session的开始时间和结束时间
                            Date actionTime = DateUtils.parseTime(row.getString(4));
                            if (startTime == null) {
                                startTime = actionTime;
                            }
                            if (endTime == null) {
                                endTime = actionTime;
                            }
                            if (actionTime.before(startTime)) {
                                startTime = actionTime;
                            }
                            if (actionTime.after(endTime)) {
                                endTime = actionTime;
                            }

                            // 计算访问步长
                            stepLength++;
                        }

                        // 截取字符串中两端的“，”， 得到搜索关键字和点击品类
                        String searchKeywords = StringUtils.trimComma(searchKeywordsBuffer.toString());
                        String clickCategoryIds = StringUtils.trimComma(clickCategoryIdsBuffer.toString());

                        // 计算访问时长，单位为秒
                        long visitLenth = (endTime.getTime() - startTime.getTime()) / 1000;

                        // 聚合数据，数据以字符串拼接的方式：key=value|key=value|key=value
                        String partAggrInfo = Constants.FIELD_SESSION_ID + "=" + sessionId + "|"
                                + Constants.FIELD_SEARCH_KEYWORDS + "=" + searchKeywords + "|"
                                + Constants.FIELD_CLICK_CATEGORY_IDS + "=" + clickCategoryIds + "|"
                                + Constants.FIELD_VISIT_LENGTH + "=" + visitLenth + "|"
                                + Constants.FIELD_STEP_LENGTH + "=" + stepLength + "|"
                                + Constants.FIELD_START_TIME + "=" + DateUtils.formatTime(startTime) + "|";

                        return new Tuple2<Long, String>(userId, partAggrInfo);
                    }
                });

        // 查询所有用户数据，构建成<userId, Row>格式
        String sql = "select * from user_info";
        JavaRDD<Row> userInfoRDD = sqlContext.sql(sql).javaRDD();
        JavaPairRDD<Long, Row> userId2InfoRDD = userInfoRDD.mapToPair(new PairFunction<Row, Long, Row>() {
            @Override
            public Tuple2<Long, Row> call(Row row) throws Exception {
                return new Tuple2<Long, Row>(row.getLong(0), row);
            }
        });

        // 将session粒度的聚合数据和用户信息进行join， 格式为：<userId, <sessionInfo, userInfo>>
        JavaPairRDD<Long, Tuple2<String, Row>> userId2FullInfoRDD =
                userId2PartAggrInfoRDD.join(userId2InfoRDD);

        // 对join后的数据进行重新拼接，返回格式为：<sessionId, fullAggrInfo>
        JavaPairRDD<String, String> sessionId2FullAggrInfoRDD = userId2FullInfoRDD.mapToPair(
                new PairFunction<Tuple2<Long, Tuple2<String, Row>>, String, String>() {
                    @Override
                    public Tuple2<String, String> call(Tuple2<Long, Tuple2<String, Row>> tup) throws Exception {
                        // 获取sessionId对应的聚合数据
                        String partAggrInfo = tup._2._1;
                        // 获取用户信息
                        Row userInfoRow = tup._2._2;

                        // 获取sessionId
                        String sessoinId = StringUtils.getFieldFromConcatString(
                                partAggrInfo, "\\|", Constants.FIELD_SESSION_ID);

                        // 提取用户信息的age
                        int age = userInfoRow.getInt(3);

                        // 提取用户信息的职业
                        String professional = userInfoRow.getString(4);

                        // 提取用户信息的所在城市
                        String city = userInfoRow.getString(5);

                        // 提取用户信息的性别
                        String sex = userInfoRow.getString(6);

                        // 拼接
                        String fullAggrInfo = partAggrInfo
                                + Constants.FIELD_AGE + "=" + age + "|"
                                + Constants.FIELD_PROFESSIONAL + "=" + professional + "|"
                                + Constants.FIELD_CITY + "=" + city + "|"
                                + Constants.FIELD_SEX + "=" + sex + "|";

                        return new Tuple2<String, String>(sessoinId, fullAggrInfo);
                    }
                });

        return sessionId2FullAggrInfoRDD;
    }

    /**
     * 获取sessionId对应的行为数据，生成session粒度的数据
     *
     * @param actionRDD
     * @return
     */
    private static JavaPairRDD<String, Row> getSessionId2ActionRDD(
            JavaRDD<Row> actionRDD) {
        return actionRDD.mapPartitionsToPair(new PairFlatMapFunction<Iterator<Row>, String, Row>() {
            @Override
            public Iterable<Tuple2<String, Row>> call(Iterator<Row> it) throws Exception {
                // 用来封装基础数据
                ArrayList<Tuple2<String, Row>> list = new ArrayList<Tuple2<String, Row>>();
                while (it.hasNext()) {
                    Row row = it.next();
                    list.add(new Tuple2<String, Row>(row.getString(2), row));
                }

                return list;
            }
        });
    }
}
