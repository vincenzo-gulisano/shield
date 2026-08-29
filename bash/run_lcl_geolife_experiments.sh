# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.01.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.02.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.04.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/lcl.flow.08.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.01.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.02.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.04.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 32 -f src/main/resources/configs/geolife.mobility.08.nsga2.txt

# rename folders to geolife_oldpriv geolife_newpriv geolife_newpriv_dag and geolife_newpriv_dag_prov (and the same for lvl_flow)

base_folder=/Users/vinmas/repositories/Shield/vincenzo/shield2/all2

# python python/extract_unique_percentile_solutions.py $base_folder/geolife_oldpriv/solutions-percentile.csv $base_folder/geolife_oldpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/geolife_newpriv/solutions-percentile.csv $base_folder/geolife_newpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/geolife_newpriv_dag/solutions-percentile.csv $base_folder/geolife_newpriv_dag/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/geolife_newpriv_dag_prov/solutions-percentile.csv $base_folder/geolife_newpriv_dag_prov/individuals

# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_oldpriv/solutions-percentile.csv $base_folder/lcl_flow_oldpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_newpriv/solutions-percentile.csv $base_folder/lcl_flow_newpriv/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_newpriv_dag/solutions-percentile.csv $base_folder/lcl_flow_newpriv_dag/individuals
# python python/extract_unique_percentile_solutions.py $base_folder/lcl_flow_newpriv_dag_prov/solutions-percentile.csv $base_folder/lcl_flow_newpriv_dag_prov/individuals

python python/extract_ranked_solution_queries.py \
  --csvs $base_folder/geolife_oldpriv/individuals/unique_solutions.csv \
  --ids shield1-oldPriv \
  -i 20 \
  --top-one-per-seed \
  -o $base_folder/geolife_oldpriv_top20_queries.csv

bash bash/rescore_ranked_solution_queries.sh \
  geolife-mobility \
  $base_folder/geolife_oldpriv_top20_queries.csv \
  $base_folder/geolife_oldpriv_top20_true_rank.csv

python python/plot_rescored_ranked_solution_scores.py \
  --write-csv \
  --csvs $base_folder/geolife_oldpriv_top20_true_rank.csv \
  -o $base_folder/geolife_oldpriv_top20_true_rank_plot.pdf

python python/extract_ranked_solution_queries.py \
  --csvs $base_folder/lcl_flow_oldpriv/individuals/unique_solutions.csv \
  --ids shield1-oldPriv \
  -i 20 \
  --top-one-per-seed \
  -o $base_folder/lcl_flow_oldpriv_top20_queries.csv

bash bash/rescore_ranked_solution_queries.sh \
  lcl-flow \
  $base_folder/lcl_flow_oldpriv_top20_queries.csv \
  $base_folder/lcl_flow_oldpriv_top20_true_rank.csv

python python/plot_rescored_ranked_solution_scores.py \
  --write-csv \
  --csvs $base_folder/lcl_flow_oldpriv_top20_true_rank.csv \
  -o $base_folder/lcl_flow_oldpriv_top20_true_rank_plot.pdf

python python/plot_ranked_solution_scores.py --write-csv --top-one-per-seed --csvs $base_folder/geolife_newpriv/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-newPriv shield2 shield2_prov -i 20 -o $base_folder/geolife_all.pdf
python python/plot_ranked_solution_scores.py --write-csv --top-one-per-seed --csvs $base_folder/lcl_flow_newpriv/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-newPriv shield2 shield2_prov -i 20 -o $base_folder/lcl_flow_all.pdf

python python/plot_ranked_solution_operator_counts.py --write-csv --csvs $base_folder/geolife_oldpriv/individuals/unique_solutions.csv $base_folder/geolife_newpriv/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag/individuals/unique_solutions.csv $base_folder/geolife_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o $base_folder/geolife_all_ops.pdf
python python/plot_ranked_solution_operator_counts.py --write-csv --csvs $base_folder/lcl_flow_oldpriv/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag/individuals/unique_solutions.csv $base_folder/lcl_flow_newpriv_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o $base_folder/lcl_flow_all_ops.pdf

python python/plot_solutions_percentile_convergence.py \
  $base_folder/geolife_newpriv_dag_prov/solutions-percentile.csv \
  --write-csv \
  --title geolife-08 \
  -o $base_folder/geolife_08_convergence.pdf

python python/plot_solutions_percentile_convergence.py \
  $base_folder/lcl_flow_newpriv_dag_prov/solutions-percentile.csv \
  --write-csv \
  --title lcl-flow-08 \
  -o $base_folder/lcl_flow_08_convergence.pdf
#########################

# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 1 -f src/main/resources/configs/lcl.flow.02.true-rank.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 1 -f src/main/resources/configs/lcl.flow.04.true-rank.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 1 -f src/main/resources/configs/lcl.flow.08.true-rank.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 1 -f src/main/resources/configs/geolife.mobility.02.true-rank.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 1 -f src/main/resources/configs/geolife.mobility.04.true-rank.nsga2.txt
# java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 1 -f src/main/resources/configs/geolife.mobility.08.true-rank.nsga2.txt

# mv geolife.oldpriv geolife_oldpriv 
# mv geolife.newpriv1 geolife_newpriv1
# mv geolife.newpriv2 geolife_newpriv2
# mv geolife.newpriv1.dag geolife_newpriv1_dag
# mv geolife.newpriv2.dag geolife_newpriv2_dag
# mv geolife.newpriv1.dag.prov geolife_newpriv1_dag_prov
# mv geolife.newpriv2.dag.prov geolife_newpriv2_dag_prov

# mv lcl.flow.oldpriv lcl_flow_oldpriv 
# mv lcl.flow.newpriv1 lcl_flow_newpriv1
# mv lcl.flow.newpriv2 lcl_flow_newpriv2
# mv lcl.flow.newpriv1.dag lcl_flow_newpriv1_dag
# mv lcl.flow.newpriv2.dag lcl_flow_newpriv2_dag
# mv lcl.flow.newpriv1.dag.prov lcl_flow_newpriv1_dag_prov
# mv lcl.flow.newpriv2.dag.prov lcl_flow_newpriv2_dag_prov

# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/geolife_oldpriv/solutions-percentile.csv vincenzo/shield2/all/geolife_oldpriv/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/geolife_newpriv1/solutions-percentile.csv vincenzo/shield2/all/geolife_newpriv1/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/geolife_newpriv2/solutions-percentile.csv vincenzo/shield2/all/geolife_newpriv2/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/geolife_newpriv1_dag/solutions-percentile.csv vincenzo/shield2/all/geolife_newpriv1_dag/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/geolife_newpriv2_dag/solutions-percentile.csv vincenzo/shield2/all/geolife_newpriv2_dag/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/geolife_newpriv1_dag_prov/solutions-percentile.csv vincenzo/shield2/all/geolife_newpriv1_dag_prov/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/geolife_newpriv2_dag_prov/solutions-percentile.csv vincenzo/shield2/all/geolife_newpriv2_dag_prov/individuals

# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/lcl_flow_oldpriv/solutions-percentile.csv vincenzo/shield2/all/lcl_flow_oldpriv/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/lcl_flow_newpriv1/solutions-percentile.csv vincenzo/shield2/all/lcl_flow_newpriv1/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/lcl_flow_newpriv2/solutions-percentile.csv vincenzo/shield2/all/lcl_flow_newpriv2/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/lcl_flow_newpriv1_dag/solutions-percentile.csv vincenzo/shield2/all/lcl_flow_newpriv1_dag/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/lcl_flow_newpriv2_dag/solutions-percentile.csv vincenzo/shield2/all/lcl_flow_newpriv2_dag/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/lcl_flow_newpriv1_dag_prov/solutions-percentile.csv vincenzo/shield2/all/lcl_flow_newpriv1_dag_prov/individuals
# python python/extract_unique_percentile_solutions.py vincenzo/shield2/all/lcl_flow_newpriv2_dag_prov/solutions-percentile.csv vincenzo/shield2/all/lcl_flow_newpriv2_dag_prov/individuals

# python python/plot_ranked_solution_scores.py --csvs vincenzo/shield2/all/geolife_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv1/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv1_dag/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv1_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/geolife_all_priv1.pdf
# python python/plot_ranked_solution_scores.py --csvs vincenzo/shield2/all/geolife_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv2/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv2_dag/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv2_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/geolife_all_priv2.pdf
# python python/plot_ranked_solution_scores.py --csvs vincenzo/shield2/all/lcl_flow_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv1/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv1_dag/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv1_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/lcl_flow_all_priv1.pdf
# python python/plot_ranked_solution_scores.py --csvs vincenzo/shield2/all/lcl_flow_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv2/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv2_dag/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv2_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/lcl_flow_all_priv2.pdf

# python python/plot_ranked_solution_operator_counts.py --csvs vincenzo/shield2/all/geolife_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv1/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv1_dag/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv1_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/geolife_all_priv1_ops.pdf
# python python/plot_ranked_solution_operator_counts.py --csvs vincenzo/shield2/all/geolife_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv2/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv2_dag/individuals/unique_solutions.csv vincenzo/shield2/all/geolife_newpriv2_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/geolife_all_priv2_ops.pdf
# python python/plot_ranked_solution_operator_counts.py --csvs vincenzo/shield2/all/lcl_flow_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv1/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv1_dag/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv1_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/lcl_flow_all_priv1_ops.pdf
# python python/plot_ranked_solution_operator_counts.py --csvs vincenzo/shield2/all/lcl_flow_oldpriv/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv2/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv2_dag/individuals/unique_solutions.csv vincenzo/shield2/all/lcl_flow_newpriv2_dag_prov/individuals/unique_solutions.csv --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 20 -o vincenzo/shield2/all/lcl_flow_all_priv2_ops.pdf