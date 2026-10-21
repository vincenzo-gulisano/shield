# Evolutionary synthesis of privacy-preserving stream preprocessing

This repository contains the implementation and experiments for the current paper on automatically synthesizing stream preprocessing queries. A preprocessing query transforms sensitive input before it is shared, aiming to preserve both the outputs and the workload characteristics of a fixed target query. JGEA performs multi-objective evolutionary search; Liebre executes the generated streaming queries.

The search maximizes three scores: **privacy** under the selected linkage or dispersion measure, **semantics** (similarity of target-query outputs), and **fidelity** (similarity of tuple/key-count profiles through the target query). Higher scores are better. Linkage privacy measures resistance to the configured attacker; it is not an unconditional privacy guarantee.

The experiments compare a port of SHIELD with progressively richer synthesis configurations. The complete configuration supports structured fork--join graphs, type-aware transformations, and provenance-based knowledge of which input tuples contribute to target-query outputs.

## Experiment configurations and goals

Each configuration is evaluated on both use cases below.

| Paper label | Config ID | Privacy objective | Preprocessing search space | Purpose |
| --- | --- | --- | --- | --- |
| **B** | `01` | Dispersion, 99th percentile (`K_ANONYMITY_CARDINALITY_Q99`) | Baseline operators in a linear pipeline | Port of SHIELD used as the comparison baseline. |
| **K** | `02` | Top-20 containment (`LINKAGE_ATTACK_TOP_K_CONTAINMENT`) | Same pipeline and operators as B | Test the effect of directly optimizing resistance to the linkage attacker. |
| **KG** | `04` | Top-20 containment | Structured fork--join graphs and extended, type-aware operators | Test the combined value of richer structures and operators. |
| **KGP** | `08` | Top-20 containment | KG plus provenance-aware operators and contributor-based branching | Test the additional value of target-query context; this is the complete approach. |

B retains the baseline privacy and fidelity measures, but is not an exact reproduction of SHIELD: semantics also compares output values, and the evolutionary implementation supports both subtree mutation and crossover. K--KG changes graph structure and type-aware operators together; it does not isolate these two effects separately.

The evaluation has four goals:

1. **Privacy validity:** rescore B solutions with top-20 containment to check whether high dispersion-based privacy translates into resistance to the explicit linkage attack.
2. **Synthesis ablations:** compare K, KG, and KGP in privacy, semantics, fidelity, and operator use.
3. **Search behavior:** inspect objective trajectories and balanced-score convergence for KGP.
4. **Runtime cost:** measure throughput, latency, graph complexity, and selectivity of selected solutions from all four configurations.

## Use cases and data

The prepared inputs and grammars are included in `src/main/resources/`; downloading raw data or regenerating grammars is not required to run the paper experiments.

| Use case | Prepared input, relative to `src/main/resources/` | Target query | Original-input query result |
| --- | --- | --- | --- |
| **LCL**, filtering-oriented | `datasets/lcl_flow_daily_10days_100keys.csv` | Filter inactive household-days; screen load profiles using tariff-specific risk scores; summarize alerts by day and tariff. | 997 tuples, 100 households, 10 days → 20 output tuples; selectivity about 2.0%. |
| **GeoLife**, aggregation-oriented | `datasets/geolife_60mins.csv` | Aggregate 6-hour user visits and retain stationary visits; map them to 2 km cells; aggregate daily cell activity and retain multi-user hotspots. | 55,362 tuples, 182 users → 259 output tuples; selectivity about 0.47%. |

Selectivity means output tuples divided by input tuples. These table values describe the **target queries on original inputs**; the runtime graph-metric plots instead report the selectivity of the **synthesized preprocessing queries**.

The original data sources are [SmartMeter Energy Consumption Data in London Households](https://data.london.gov.uk/dataset/smartmeter-energy-consumption-data-in-london-households-vqm0d) and [GeoLife GPS Trajectories](https://www.microsoft.com/en-us/download/details.aspx?id=52367). LCL input contains eleven daily load-profile features; GeoLife input contains hourly average projected coordinates. `python/select_lcl_day.py` can prepare LCL daily features from raw shards; use `--help` for its date-range, household-selection, and `--flow-features` options.

## Requirements and build

- **JDK 25** for the paper setup; `pom.xml` targets Java 25.
- **Maven** for the Java build.
- **Python 3.10 or newer** for extraction and plotting, with **Matplotlib 3.9 or newer** and NumPy. The plotting code uses Matplotlib's `tick_labels` boxplot argument.
- **Graphviz** (`dot` on `PATH`) only if rendering individual query graphs; it is optional when extraction uses `--png-limit 0` as below.

Build and install the customized Liebre dependency from its `rich_agg` branch, then build this project. The commands below assume Shield and Liebre are sibling checkouts; run them from the Shield root:

```bash
git clone --branch rich_agg https://github.com/vincenzo-gulisano/Liebre.git ../Liebre
mvn -f ../Liebre/pom.xml clean install
mvn clean package
```

If Liebre is already checked out, use that checkout instead of cloning it again. The required Maven artifact is `com.vincenzogulisano:liebre:0.1.1`. Maven also resolves JGEA `2.8.2-SNAPSHOT` and its transitive dependencies from the repositories declared in the POMs. If that snapshot is unavailable, install the matching JGEA source version locally before building; an arbitrary newer JGEA version is not guaranteed to accept these experiment files.

Set up the Python environment:

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install 'matplotlib>=3.9' numpy
```

All remaining commands run from the Shield repository root, with that environment active. They use:

```bash
jar=target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar
results=outputs/paper
threads=32
```

Choose a fresh `results` directory for an independent rerun, because the commands below use fixed output filenames. Input paths such as `datasets/geolife_60mins.csv` are **classpath resources**, not arbitrary filesystem paths: after changing a resource, rebuild the JAR. Grammar paths are repository-relative files.

## 1. Run the evolutionary searches

The paper uses the following eight configuration files:

| Use case | B | K | KG | KGP |
| --- | --- | --- | --- | --- |
| LCL | `lcl.flow.01.nsga2.txt` | `lcl.flow.02.nsga2.txt` | `lcl.flow.04.nsga2.txt` | `lcl.flow.08.nsga2.txt` |
| GeoLife | `geolife.mobility.01.nsga2.txt` | `geolife.mobility.02.nsga2.txt` | `geolife.mobility.04.nsga2.txt` | `geolife.mobility.08.nsga2.txt` |

All are under `src/main/resources/configs/`. Each file already runs **30 seeds (1--30)** with **NSGA-II**, population **100**, a budget of **20,000 quality evaluations**, tree heights **7--20**, `k=20`, and semantics matching threshold **0.02**. One invocation runs all seeds for that configuration; do not repeat it thirty times.

To launch one configuration with its default timestamped output directory:

```bash
java -Djava.awt.headless=true -jar "$jar" -v -nt "$threads" -nr 1 \
  -f src/main/resources/configs/lcl.flow.08.nsga2.txt
```

`-nt` controls fitness-evaluation threads, `-nr 1` runs one evolutionary seed at a time, and `-v` enables verbose errors. These control search concurrency, not the parallelism of an individual Liebre query. Adjust `threads` to the machine.

To launch **all eight configurations**, the following loop creates local copies of the configs with predictable output directories, then runs them sequentially. It changes only each copied config's `$basePath`; the checked-in configs remain unchanged.

```bash
mkdir -p "$results/configs"
for use_case in lcl_flow geolife; do
  case "$use_case" in
    lcl_flow) family=lcl.flow ;;
    geolife) family=geolife.mobility ;;
  esac
  for setup in 01 02 04 08; do
    case "$setup" in
      01) variant=oldpriv ;;
      02) variant=newpriv ;;
      04) variant=newpriv_dag ;;
      08) variant=newpriv_dag_prov ;;
    esac
    run_dir="$results/${use_case}_${variant}"
    run_config="$results/configs/${family}.${setup}.nsga2.txt"
    mkdir -p "$run_dir"
    sed "1s|.*|\$basePath = ''${run_dir}/''|" \
      "src/main/resources/configs/${family}.${setup}.nsga2.txt" > "$run_config"
    java -Djava.awt.headless=true -jar "$jar" -v -nt "$threads" -nr 1 \
      -f "$run_config" || exit 1
  done
done
```

The rest of this README assumes that layout. With the original configs, output instead goes under `outputs/{experiment-name}/{start-time}/`; use those paths or organize the completed runs into the same layout.

Each search writes:

- `solutions-percentile.csv`: recorded solutions and their three scores at each iteration, for the 75th and 100th percentiles of each objective.
- `final-firsts.csv`: final non-dominated solutions.
- Per-seed scatterplots and experiment-level SVG/TSV trajectories, including `p75-privacy`, `p75-semantics`, and `p75-fidelity`, plus the other objective values of the corresponding percentile representatives.

The published selection workflow below uses the recorded percentile representatives in `solutions-percentile.csv`, not every candidate evaluated during search. A short installation check can use a copied config with fewer seeds and a smaller evaluation budget; such a run does not reproduce the paper settings.

## 2. Extract and rank recorded solutions

Deduplicate the recorded query graphs and export their scores:

```bash
for use_case in lcl_flow geolife; do
  for variant in oldpriv newpriv newpriv_dag newpriv_dag_prov; do
    run_dir="$results/${use_case}_${variant}"
    python python/extract_unique_percentile_solutions.py \
      "$run_dir/solutions-percentile.csv" "$run_dir/individuals" --png-limit 0
  done
done
```

This creates `individuals/unique_solutions.csv` in each run directory. To also draw the top twenty graphs, use `--png-limit 20` with Graphviz installed.

The subsequent commands use `--top-one-per-seed -i 30`: select one representative per seed, maximizing `min(privacy, semantics, fidelity)`, with tie-breaking by distance from the ideal score vector and then individual scores. This balanced minimum is a selection/convergence diagnostic, not a fourth search objective. The legacy script IDs map as follows:

| Paper label | Script ID | Run-directory suffix |
| --- | --- | --- |
| B | `shield1-oldPriv` | `oldpriv` |
| K | `shield1-newPriv` | `newpriv` |
| KG | `shield2` | `newpriv_dag` |
| KGP | `shield2_prov` | `newpriv_dag_prov` |

If a CSV contains fewer than thirty distinct recorded seeds, the per-seed selection reports an error; inspect the search outputs before reducing `-i`.

## 3. Test dispersion privacy against the linkage attacker

Select thirty B representatives using their original scores, then reevaluate those **same queries** under top-20 containment. This tests whether the original privacy objective measures protection against the configured attack; it does not rerun the evolutionary search.

```bash
for use_case in lcl_flow geolife; do
  case "$use_case" in
    lcl_flow) query=lcl-flow ;;
    geolife) query=geolife-mobility ;;
  esac
  python python/extract_ranked_solution_queries.py \
    --csvs "$results/${use_case}_oldpriv/individuals/unique_solutions.csv" \
    --ids shield1-oldPriv --top-one-per-seed -i 30 \
    -o "$results/${use_case}_oldpriv_top30_queries.csv"
  bash bash/rescore_ranked_solution_queries.sh "$query" \
    "$results/${use_case}_oldpriv_top30_queries.csv" \
    "$results/${use_case}_oldpriv_top30_top_k.csv"
  python python/plot_rescored_ranked_solution_scores.py --write-csv \
    --csvs "$results/${use_case}_oldpriv_top30_top_k.csv" \
    -o "$results/${use_case}_oldpriv_top30_top_k_plot.pdf"
done
```

The rescoring wrapper defaults to `LINKAGE_ATTACK_TOP_K_CONTAINMENT`, `k=20`, and semantics threshold `0.02`, using the appropriate bundled input. Its output retains original scores and adds `rescored_*` columns. The plot compares original and rescored privacy as distributions and paired points; its CSV exports `old_privacy,new_privacy`.

## 4. Compare K, KG, and KGP

Plot the quality trade-offs and count operator occurrences among the selected representatives:

```bash
for use_case in lcl_flow geolife; do
  python python/plot_ranked_solution_scores.py --write-csv --top-one-per-seed \
    --csvs "$results/${use_case}_newpriv/individuals/unique_solutions.csv" \
           "$results/${use_case}_newpriv_dag/individuals/unique_solutions.csv" \
           "$results/${use_case}_newpriv_dag_prov/individuals/unique_solutions.csv" \
    --ids shield1-newPriv shield2 shield2_prov -i 30 \
    -o "$results/${use_case}_all.pdf"
  python python/plot_ranked_solution_operator_counts.py --write-csv --top-one-per-seed \
    --csvs "$results/${use_case}_newpriv/individuals/unique_solutions.csv" \
           "$results/${use_case}_newpriv_dag/individuals/unique_solutions.csv" \
           "$results/${use_case}_newpriv_dag_prov/individuals/unique_solutions.csv" \
    --ids shield1-newPriv shield2 shield2_prov -i 30 \
    -o "$results/${use_case}_all_ops.pdf"
done
```

The first plot compares pairs of objective scores; the second reports operator-type totals across the selected queries. Both export the plotted data beside the PDF. B is omitted here because its original privacy values use a different objective; compare its rescored values when using the top-k scale.

## 5. Inspect KGP convergence

The config-generated `p75-*.svg` files show individual-objective trajectories. The following adds a balanced-score view for the complete configuration:

```bash
for use_case in lcl_flow geolife; do
  python python/plot_solutions_percentile_convergence.py \
    "$results/${use_case}_newpriv_dag_prov/solutions-percentile.csv" \
    --write-csv --max-mode actual --title "$use_case KGP" \
    -o "$results/${use_case}_08_convergence.pdf"
done
```

At each iteration and seed, the script takes the largest `min(privacy, semantics, fidelity)` among the recorded percentile representatives. It plots the median across seeds reporting that iteration, with a Q1--Q3 band. `--max-mode actual` shows that iteration's value, which can decrease; `cumulative` instead shows the best value recorded so far. The CSV contains `x,q1,q2,q3`, where `q2` is the median. Later points need not include all thirty seeds if some runs end earlier.

## 6. Benchmark throughput, latency, and graph complexity

These benchmarks execute **the synthesized preprocessing queries**, one individual at a time. They repeatedly replay the prepared input, advance event timestamps between loops, and measure processed input tuples/s and source-to-output latency. They do not benchmark the downstream target query. Liebre uses its default scheduler, parallelism, and runtime settings; no distributed deployment or execution tuning is involved.

### 6.1 Select benchmark queries

Create one manifest per use case, containing thirty balanced representatives from each configuration (120 queries total):

```bash
for use_case in lcl_flow geolife; do
  python python/extract_ranked_solution_queries.py --top-one-per-seed \
    --csvs "$results/${use_case}_oldpriv/individuals/unique_solutions.csv" \
           "$results/${use_case}_newpriv/individuals/unique_solutions.csv" \
           "$results/${use_case}_newpriv_dag/individuals/unique_solutions.csv" \
           "$results/${use_case}_newpriv_dag_prov/individuals/unique_solutions.csv" \
    --ids shield1-oldPriv shield1-newPriv shield2 shield2_prov -i 30 \
    -o "$results/${use_case}_benchmark_queries.csv"
done
```

Keep the manifests and their referenced `individuals/unique_solutions.csv` files available: enrichment later uses those source paths to recover graph metrics.

### 6.2 Calibrate input rates

First run an unthrottled calibration to estimate achievable rates on the benchmark machine, then generate a stepped-rate plan:

```bash
for use_case in lcl_flow geolife; do
  case "$use_case" in
    lcl_flow) query=lcl-flow ;;
    geolife) query=geolife-mobility ;;
  esac
  bash bash/benchmark_ranked_solution_queries.sh "$query" \
    "$results/${use_case}_benchmark_queries.csv" \
    "$results/perf_${use_case}_calibration" 1 60 20000 20000
  python python/suggest_performance_rate_steps.py \
    "$results/perf_${use_case}_calibration/index.csv" \
    -o "$results/${use_case}_rate_plan.csv" \
    --steps 6 --step-seconds 30 --bucket-millis 50
done
```

Here calibration uses one repetition of at least 60 seconds, with 20-second warm-up and cool-down intervals. This is a rate-estimation step, not the final measurement. The helper takes each setup's mean of `min(input throughput, output throughput)` over successful calibration runs as the final offered rate, starting at one sixth of that rate. Review or adjust the resulting plans if they do not span a useful load range; these suggestions are not certified saturation limits.

### 6.3 Run the stepped benchmarks

Use the same benchmark machine without competing searches or benchmarks:

```bash
for use_case in lcl_flow geolife; do
  case "$use_case" in
    lcl_flow) query=lcl-flow ;;
    geolife) query=geolife-mobility ;;
  esac
  bash bash/benchmark_ranked_solution_queries_stepped.sh "$query" \
    "$results/${use_case}_benchmark_queries.csv" \
    "$results/${use_case}_rate_plan.csv" \
    "$results/perf_${use_case}_steps" 3 20000 20000 30
done
```

The positional arguments after the output directory are **3 repetitions**, **20,000 ms warm-up**, **20,000 ms cool-down**, and **30 seconds extra timeout allowance**. Each plan above has six 30-second rate steps (180 seconds per repetition), increasing linearly from its start to final rate. This is 360 measured repetitions per use case, about 18 hours of scheduled input replay before startup/draining overhead. The wrapper prints a runtime estimate. `SHIELD_JAR` can override the default JAR path for either benchmark wrapper and the rescoring wrapper.

Each benchmark directory contains `index.csv` with run status and summary values, plus per-second measurement CSVs. Check failed rows before interpreting the plots; the enrichment script processes successful runs only.

### 6.4 Enrich and plot benchmark results

```bash
for use_case in lcl_flow geolife; do
  perf_dir="$results/perf_${use_case}_steps"
  python python/enrich_stepped_performance_index.py "$perf_dir/index.csv" \
    -o "$perf_dir/index_enriched.csv"
  python python/plot_performance_summary.py "$perf_dir/index_enriched.csv" \
    --selection max-throughput -o "$perf_dir/${use_case}_performance_summary.pdf"
  python python/plot_performance_graph_metrics.py "$perf_dir/index_enriched.csv" \
    --selection max-throughput -o "$perf_dir/${use_case}_performance_graph_metrics.pdf"
done
```

Enrichment creates a row per rate step and diagnostic plots per repetition. The plotting scripts select the maximum-input-throughput step independently in each repetition, then average the selected measurements for each query before forming the setup boxplots. They require the **enriched per-step CSV**, not an older exported plot-summary CSV.

Warm-up/cool-down are excluded from the benchmark's whole-run summary, but the per-step enrichment retains all rate steps and flags overlap. **`--selection max-throughput` does not filter out those flagged steps**; it matches the current plotting workflow. The optional `latency-filtered` selection instead excludes steps overlapping warm-up and requires maximum latency no greater than three times average latency; it also changes which step is selected.

The runtime plots show input throughput and average latency. Graph diagnostics show total operators, stateful operators, branch/join count, and preprocessing selectivity. Total operators exclude source, sink, and union nodes; branch/join count is `max(number of branch points, number of join points)`, not the number of branches.

The scripts automatically write six boxplot-summary CSVs per use case, using the PDF stem:

| Script | CSV suffixes |
| --- | --- |
| `plot_performance_summary.py` | `_input_throughput.csv`, `_latency.csv` |
| `plot_performance_graph_metrics.py` | `_total_operators.csv`, `_stateful_operators.csv`, `_branches_joins.csv`, `_output_input_ratio.csv` |

`_output_input_ratio` is the retained filename for selectivity. Each CSV has four setup rows in B, K, KG, KGP order, with columns `setup,whisker_low,q1,q2,q3,whisker_up,outliers`. Setup names match the plots' full x-axis labels, flattened onto one line. `q2` is the median; whiskers end at observed values within the 1.5-IQR fences, and multiple outliers in a cell are separated by `|`.

## Repository map and other configurations

- `src/main/java/usecase/lcl/flow/`: LCL target query, objectives, and contributor conditions.
- `src/main/java/usecase/geolife/mobility/`: GeoLife target query, objectives, and contributor conditions.
- `src/main/java/grammar/generator/`, `src/main/java/mappers/`, and `src/main/java/query/`: typed grammar generation, graph mapping, Liebre execution, and preprocessing operators (under `query/utils/`).
- `src/main/resources/configs/` and `src/main/resources/grammars/`: experiment definitions and checked-in search spaces. GeoLife K intentionally reuses the `01` grammar.
- `src/main/java/usecase/analysis/` and its `performance/` subpackage: rescoring and isolated runtime benchmarks.
- `python/`: extraction, ranking, plotting, data preparation, and benchmark enrichment.
- `bash/`: Java command wrappers and the working experiment command notebook.

`bash/run_lcl_geolife_experiments.sh` records the development workflow, but has a machine-specific `base_folder` and mostly commented commands; it is not an end-to-end runner. Use the commands above for a fresh run.

Other numbered LCL configs, alternative grammars, and `*.true-rank.nsga2.txt` files are exploratory variants. True-rank configurations use a different linkage objective; they are not part of the paper's top-k-containment evaluation. The root `experiment*.txt`, AirQuality, and the legacy GeoLife query belong to the earlier experiments and are not the entry points for this paper.
