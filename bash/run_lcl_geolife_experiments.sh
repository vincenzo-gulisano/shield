# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.01.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.02.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.04.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.08.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.01.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.02.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.04.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.08.nsga2.txt

# rename folders to geolife_oldpriv geolife_newpriv geolife_newpriv_dag and geolife_newpriv_dag_prov (and the same for lvl_flow)

base_folder=/Users/vinmas/repositories/Shield/vincenzo/shield2/all3

# python python/extract_unique_percentile_solutions.py $base_folder/geolife_oldpriv/solutions-percentile.csv $base_folder/geolife_oldpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/geolife_newpriv/solutions-percentile.csv $base_folder/geolife_newpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/geolife_newpriv_dag/solutions-percentile.csv $base_folder/geolife_newpriv_dag/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/geolife_newpriv_dag_prov/solutions-percentile.csv $base_folder/geolife_newpriv_dag_prov/individuals

# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_oldpriv/solutions-percentile.csv $base_folder/lcl_flow_oldpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_newpriv/solutions-percentile.csv $base_folder/lcl_flow_newpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_newpriv_dag/solutions-percentile.csv $base_folder/lcl_flow_newpriv_dag/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_newpriv_dag_prov/solutions-percentile.csv $base_folder/lcl_flow_newpriv_dag_prov/individuals

# python python/extract_ranked_solution_queries.py --csvs $base_folder/geolife_oldpriv/individuals/unique_solutions.csv --ids shield1-oldPriv -i 30 --top-one-per-seed -o $base_folder/geolife_oldpriv_top30_queries.csv

# bash bash/rescore_ranked_solution_queries.sh geolife-mobility $base_folder/geolife_oldpriv_top30_queries.csv $base_folder/geolife_oldpriv_top30_top_k.csv

# python python/plot_rescored_ranked_solution_scores.py --write-csv --csvs $base_folder/geolife_oldpriv_top30_top_k.csv -o $base_folder/geolife_oldpriv_top30_top_k_plot.pdf

# python python/extract_ranked_solution_queries.py --csvs $base_folder/lcl_flow_oldpriv/individuals/unique_solutions.csv --ids shield1-oldPriv -i 30 --top-one-per-seed -o $base_folder/lcl_flow_oldpriv_top30_queries.csv

# bash bash/rescore_ranked_solution_queries.sh lcl-flow $base_folder/lcl_flow_oldpriv_top30_queries.csv $base_folder/lcl_flow_oldpriv_top30_top_k.csv

# python python/plot_rescored_ranked_solution_scores.py --write-csv --csvs $base_folder/lcl_flow_oldpriv_top30_top_k.csv -o $base_folder/lcl_flow_oldpriv_top30_top_k_plot.pdf

# python python/plot_ranked_solution_scores.py --write-csv --top-one-per-seed --csvs $base_folder/geolife_newpriv/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-newPriv shield2 shield2_prov -i 30 -o $base_folder/geolife_all.pdf
# python python/plot_ranked_solution_scores.py --write-csv --top-one-per-seed --csvs $base_folder/lcl_flow_newpriv/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-newPriv shield2 shield2_prov -i 30 -o $base_folder/lcl_flow_all.pdf

# python python/plot_ranked_solution_operator_counts.py --write-csv --top-one-per-seed --csvs $base_folder/geolife_newpriv/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-newPriv shield2 shield2_prov -i 30 -o $base_folder/geolife_all_ops.pdf
# python python/plot_ranked_solution_operator_counts.py --write-csv --top-one-per-seed --csvs $base_folder/lcl_flow_newpriv/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-newPriv shield2 shield2_prov -i 30 -o $base_folder/lcl_flow_all_ops.pdf

# python python/plot_solutions_percentile_convergence.py $base_folder/geolife_newpriv_dag_prov/solutions-percentile.csv --write-csv --title geolife-08 -o $base_folder/geolife_08_convergence.pdf --max-mode actual

# python python/plot_solutions_percentile_convergence.py $base_folder/lcl_flow_newpriv_dag_prov/solutions-percentile.csv --write-csv --title lcl-flow-08 -o $base_folder/lcl_flow_08_convergence.pdf --max-mode actual

# # Extracting queries to run throughput/latency benchmarks

# python python/extract_ranked_solution_queries.py --csvs $base_folder/geolife_oldpriv/individuals/unique_solutions.csv $base_folder/geolife_newpriv/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 30 --top-one-per-seed -o $base_folder/geolife_benchmark_queries.csv

# python python/extract_ranked_solution_queries.py --csvs $base_folder/lcl_flow_oldpriv/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 30 --top-one-per-seed -o $base_folder/lcl_flow_benchmark_queries.csv

# To run the queries for performance, on the server - something like that
# ./bash/benchmark_ranked_solution_queries_stepped.sh lcl-flow lcl_flow_benchmark_queries.csv suggested_rate_lcl_flow.csv outputs/perf_lcl_flow_steps_2/ 3 20000 20000 30
# there is also a script to find reasonable rates for the queries: suggest_performance_rate_steps.py

# Once you get the performance data, first you enrich the index.csv file
# python python/enrich_stepped_performance_index.py $base_folder/perf_lcl_flow_steps_2/index.csv -o $base_folder/perf_lcl_flow_steps_2/index_enriched.csv
# python python/enrich_stepped_performance_index.py $base_folder/perf_geolife_steps_2/index.csv -o $base_folder/perf_geolife_steps_2/index_enriched.csv

# And then you can create the graphs
python python/plot_performance_summary.py $base_folder/perf_lcl_flow_steps_2/index_enriched.csv -o $base_folder/perf_lcl_flow_steps_2/lcl_performance_summary.pdf --title "LCL Flow stepped performance" --selection max-throughput
python python/plot_performance_summary.py $base_folder/perf_geolife_steps_2/index_enriched.csv -o $base_folder/perf_geolife_steps_2/geolife_performance_summary.pdf --title "Geolife stepped performance" --selection max-throughput
python python/plot_performance_graph_metrics.py $base_folder/perf_lcl_flow_steps_2/index_enriched.csv -o $base_folder/perf_lcl_flow_steps_2/lcl_performance_graph_metrics.pdf --selection max-throughput
python python/plot_performance_graph_metrics.py $base_folder/perf_geolife_steps_2/index_enriched.csv -o $base_folder/perf_geolife_steps_2/geolife_performance_graph_metrics.pdf --selection max-throughput