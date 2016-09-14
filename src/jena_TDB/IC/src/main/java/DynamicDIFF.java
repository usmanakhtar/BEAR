import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.query.QueryExecution;
import com.hp.hpl.jena.query.QueryExecutionFactory;
import com.hp.hpl.jena.query.QueryFactory;
import com.hp.hpl.jena.query.QuerySolution;
import com.hp.hpl.jena.query.ResultSet;
import com.hp.hpl.jena.tdb.TDBFactory;

import arq.arq;


public class DynamicDIFF {

	private static Options options;
	
	private static CommandLine parseOption(String[] args) {
		options = new Options();
		options.addOption("h", "help", false, "show help.");
		options.addOption("d",true, "tdb folder");
		options.addOption("i", true,"query file");
		options.addOption("t", true,"type [s, p, o]");
		options.addOption("o", true,"output dir");
		
		CommandLineParser parser = new BasicParser();
		try {
			CommandLine cmd = parser.parse( options, args);
			
			if(args.length!=8 ||cmd.hasOption("h") || cmd.hasOption("help")) help();
			return cmd;
		} catch (ParseException e1) {
			System.err.println( "Parsing failed.  Reason: " + e1.getMessage() );
			help(); return null;
		}
	}
	private static void help() {
		HelpFormatter formater = new HelpFormatter();
		formater.printHelp("Main", options);
		System.exit(0);
	}
	
	
	private static void warmup(Map<Integer, Dataset> datasets) {
		// TODO Auto-generated method stub
		ResourceResult r = new ResourceResult("","");
		String query = "SELECT * WHERE{ ?s ?p ?o .} LIMIT 100";
		
		System.out.println("WARMUP");
		
		long start = System.currentTimeMillis();
		//Multi-threaded querying
		Set<QueryThread> a = new HashSet<QueryThread>();
		 
		for(Entry<Integer, Dataset>ent: datasets.entrySet()){
			QueryThread an = new QueryThread(ent.getKey(), ent.getValue(), r, query);
			an.start();
			a.add(an);
		}
		for(QueryThread an: a){
			try {
				an.join();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		System.out.println("WARUM done, "+(System.currentTimeMillis()-start));
	}
	
	public static void main(String[] args) throws IOException {
		CommandLine cmd = parseOption(args);
		
		File tdbFolder = new File(cmd.getOptionValue("d"));
		File input = new File(cmd.getOptionValue("i"));
		String type = cmd.getOptionValue("t");
		File outDir = new File(cmd.getOptionValue("o"),type);
		
		long start = System.currentTimeMillis();
		Map<Integer, Dataset> datasets =  initDatasets(tdbFolder);
		System.out.println("Loaded "+datasets.size()+" in "+(System.currentTimeMillis()-start)+" ms");
		
		warmup(datasets);
		
		if(!outDir.exists()) outDir.mkdirs();
		
		InputStream is = new FileInputStream(input);
		if(input.getName().endsWith(".gz")){
			is = new GZIPInputStream(is);
		}
		Scanner s = new Scanner(is);
		int c =0;
		
		
		TreeMap<Integer, DescriptiveStatistics> vStats= new TreeMap<Integer, DescriptiveStatistics>();
		for(int i=0; i <=datasets.size(); i++){
			vStats.put(i, new DescriptiveStatistics());
		}
		DescriptiveStatistics total = new DescriptiveStatistics();
		
		
		File res= new File(outDir,"res-dyndiff-"+input.getName()+"-details");
		res.mkdir();
		
		while(s.hasNextLine()){
			String line = s.nextLine();
					
			String[] val = line.trim().split(" ");
			if(val.length==9){
				c++;
				
				ResourceResult r = new ResourceResult(val[0], type);
				PrintWriter pwStats = new PrintWriter(new File(res, URLEncoder.encode(val[0],"UTF-8")+".stats"));
				for(int t =2; t<datasets.size();t++){
				
					//Multi-threaded querying
					Set<QueryThread> a = new HashSet<QueryThread>();
					int [] diffs = {t-1,t};
					start = System.currentTimeMillis();
				
					for(int i:diffs){
						QueryThread an = new QueryThread(i, datasets.get(i), r);
						an.start();
						a.add(an);
					}
					for(QueryThread an: a){
						try {
							an.join();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
					Map<Integer, TreeSet<QuerySolution>> results = r.getResults();
				
//					PrintWriter pw1 = new PrintWriter(new File(res, URLEncoder.encode(val[0],"UTF-8")+"-"+(t-1)+"v"+(t)+".res"));
//					
//					pw1.println("###QUERY");
//					pw1.println(r.createQuery());
//					pw1.println("###RES");
				
					TreeSet<QuerySolution> t1 = results.get(diffs[0]);
					TreeSet<QuerySolution> t2 = results.get(diffs[1]);
				
					int adds=0, dels=0;
					for(QuerySolution sol: t1){
						if(!t2.contains(sol)){
//							pw1.println("d,"+sol);
							dels++;
						}
					}
					for(QuerySolution sol: t2){
						if(!t1.contains(sol)){
//							pw1.println("a,"+sol);
							adds++;
						}
					}
					long end = System.currentTimeMillis();
					total.addValue((end-start));
					vStats.get(t).addValue((end-start));
					
					pwStats.println(val[0]+","+(t-1)+"-"+t+","+(end-start)+","+adds+","+dels);
//					pw1.close();
				}
				pwStats.close();
			}
		}
		PrintWriter pw = new PrintWriter(new File(outDir,"res-dyndiff-"+input.getName()+".csv"));
		pw.println("##ver, min, mean, max, stddev, count");
		for(Entry<Integer, DescriptiveStatistics>ent: vStats.entrySet()){
			pw.println(	ent.getKey()+" "+
						ent.getValue().getMin()+" "+
						ent.getValue().getMean()+" "+
						ent.getValue().getMax()+" "+
						ent.getValue().getStandardDeviation()+" "+
						ent.getValue().getN()
					);
		}
		pw.println("tot "+
				total.getMin()+" "+
				total.getMean()+" "+
				total.getMax()+" "+
				total.getStandardDeviation()+" "+
				total.getN()
			);
		pw.close();
	}
	
	private static Map<Integer, Dataset> initDatasets(File tdbFolder) {
		if(!tdbFolder.isDirectory()) throw new RuntimeException("tdbfolder "+tdbFolder+" is not a directory");
		
		System.out.println("Parsing files in "+tdbFolder);
		File[] files = tdbFolder.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.contains(".tdb");
			}
		});

		Map<Integer, Dataset> d = new TreeMap<Integer, Dataset>();
		
		Arrays.sort(files, new Comparator<File>(){
		    public int compare(File f1, File f2)
		    {
		    	Integer a = Integer.valueOf(f1.getName().substring(0,f1.getName().indexOf(".")));
		    	Integer b = Integer.valueOf(f2.getName().substring(0,f2.getName().indexOf(".")));
		        return a.compareTo(b);
		    } });
		for(File f: files){
			System.out.println("loading "+f);
			Integer i = Integer.valueOf(f.getName().substring(0,f.getName().indexOf(".")));
			Dataset dataset = TDBFactory.createDataset(f.getAbsolutePath()) ;
			d.put(i,dataset);
		}
		
		return d;

	}

}
