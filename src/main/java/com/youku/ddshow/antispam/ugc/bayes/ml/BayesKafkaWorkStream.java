package com.youku.ddshow.antispam.ugc.bayes.ml;

import com.google.common.collect.Lists;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.youku.ddshow.antispam.model.PropertiesType;
import com.youku.ddshow.antispam.utils.BysJava2;
import com.youku.ddshow.antispam.utils.CalendarUtil;
import com.youku.ddshow.antispam.utils.Database;
import com.youku.ddshow.antispam.utils.LaifengWordAnalyzer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.mllib.classification.NaiveBayesModel;
import org.apache.spark.mllib.linalg.Vector;
import org.apache.spark.sql.DataFrame;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SQLContext;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.Time;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka.KafkaUtils;
import scala.Tuple2;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Created by dongjian on 2016/4/22.
 *
 * 贝叶斯分类器接kafka
 */
public class BayesKafkaWorkStream {
    public  static NaiveBayesModel loadedModel = null;
    private static final Pattern SPACE = Pattern.compile("\t");
    private static  Database _db = null;
    private BayesKafkaWorkStream() {
    }

    public static void main(String[] args) {
        if (args.length < 4) {
            System.err.println("Usage: BayesKafkaWorkStream <zkQuorum> <group> <topics> <numThreads>");
            System.exit(1);
        }
        _db  =  new Database(PropertiesType.DDSHOW_STAT_ONLINE);
        SparkConf sparkConf = new SparkConf().setAppName("BayesKafkaWorkStream").setExecutorEnv("file.encoding","UTF-8");
        // Create the context with a 1 second batch size
        JavaStreamingContext jssc = new JavaStreamingContext(sparkConf, new Duration(2000));
        final SQLContext sqlContext = new SQLContext(jssc.sc());
       String modelPath = args[4];
       final NaiveBayesModel loadedModel =     NaiveBayesModel.load(jssc.sc().sc(),modelPath);

       int numThreads = Integer.parseInt(args[3]);
        Map<String, Integer> topicMap = new HashMap<String, Integer>();
        String[] topics = args[2].split(",");
        for (String topic: topics) {
            topicMap.put(topic, numThreads);
        }

        JavaPairReceiverInputDStream<String, String> messages =
                KafkaUtils.createStream(jssc, args[0], args[1], topicMap);

        JavaDStream<String> lines = messages.map(new Function<Tuple2<String, String>, String>() {
            @Override
            public String call(Tuple2<String, String> tuple2) {
                return tuple2._2();
            }
        });

        JavaDStream<String> words = lines.flatMap(new FlatMapFunction<String, String>() {
            @Override
            public Iterable<String> call(String x) {
                return Lists.newArrayList(SPACE.split(x));
            }
        });
        words.print();


        /**
         * 将JavaDStream转换为普通rdd进行处理
         */
       words.foreach(new Function2<JavaRDD<String>, Time, Void>() {
            @Override
            public Void call(JavaRDD<String> stringJavaRDD, Time time) throws Exception {
                List<String> listFromStream =   stringJavaRDD.collect();

                JavaRDD<String> resultRDD = null;
                if(listFromStream.size()>1)
                {
                    String[] ipAndToken =   listFromStream.get(listFromStream.size()-3).split("_");
                    String ip =ipAndToken[1];
                    String token = ipAndToken[3];
                    try{
                         JSONObject  dataInfo =   (JSONObject)JSON.parse(listFromStream.get(listFromStream.size()-1));
                         String content =   dataInfo.getJSONObject("dataInfo").get("content").toString();
                         String user_name = dataInfo.getJSONObject("dataInfo").get("nickName").toString();
                         String commentId = dataInfo.getJSONObject("dataInfo").get("id").toString();
                         String userLevel = dataInfo.getJSONObject("dataInfo").get("userLevel").toString();
                         String commenterId = dataInfo.getJSONObject("dataInfo").get("commenter").toString();
                         String timestamp =   dataInfo.getJSONObject("dataInfo").get("timestamp").toString();
                         System.out.println("这是要检测的内容：---》"+content+" "+user_name+" "+commentId+" "+userLevel+" "+commenterId+" "+ CalendarUtil.getDetailDateFormat(Long.parseLong(timestamp))+" "+ip+" "+token);
                         List<String>   listForModel = new ArrayList<String>();
                         listForModel.add(LaifengWordAnalyzer.wordAnalyzer(content));
                         System.out.println("这是分词结果：---》"+LaifengWordAnalyzer.wordAnalyzer(content));
                         JavaSparkContext ctx = new JavaSparkContext(stringJavaRDD.context());
                         DataFrame rescaledData =   BysJava2.String2DataFrame(listForModel, ctx,sqlContext);

                        if(rescaledData!=null)
                        {
                            String labelResult = "";
                            List<String> resultList = new ArrayList<String>();
                            for (Row r : rescaledData.select("features", "label").take(1)) {

                                Vector features = r.getAs(0);
                                Integer label =  r.getInt(1);

                                Double result =  loadedModel.predict(features);
                                if(result.shortValue()==0)
                                {
                                    System.out.println("这是条广告！！");
                                    synchronized(_db){
                                      _db.execute(String.format("insert into t_result_ugc_comment_antispam (commenterId,ip,device_token,user_name,commentId,content,stat_time,user_level) values ('%s','%s','%s','%s','%s','%s','%s','%s');"
                                                ,commenterId, ip, token,  user_name, commentId,
                                                content,CalendarUtil.getDetailDateFormat(Long.parseLong(timestamp)),userLevel));
                                    }
                                    labelResult = "这是条广告！！";
                                }else
                                {
                                    System.out.println("这是正常留言");
                                    labelResult = "这是正常留言";
                                }

                                System.out.println(result.shortValue());
                            }
                            resultList.add(content+"    "+labelResult+" "+commenterId+" "+ip+" "+token+" "+user_name);
                            resultRDD =    ctx.parallelize(resultList,1);
                            resultRDD.saveAsTextFile("/ugc_result/"+timestamp);
                        }
                       }
                      catch(Exception e)
                      {
                          e.printStackTrace();
                      }
                }
                return null;
            }
        });
        jssc.start();
        jssc.awaitTermination();
    }
}