# SHIELD: Evolutionary Synthesis of Privacy-Preserving Pipelines for Live Stream Data Sharing

This project uses a **multi-objective evolutionary algorithm**, implemented with the **JGEA** framework, to automatically discover optimal "data modifiers". A data modifier is a streaming query that transforms an original data stream (`S`) into a modified, privacy-preserving stream (`S'`). The quality of each data modifier is determined by running a fixed Liebre analysis query (Q) on the modified stream (S') and evaluating its impact on the final results and the overall performance profile.

The core idea is to find the best possible trade-offs between three competing objectives:
1. **Privacy**: How well the data are anonymized and how effectively the modified data protects against re-identification.
2. **Results Similarity**: The fidelity of the results produced by a fixed analysis query when run on the modified stream versus the original stream.
3. **Performance Similarity**: The fidelity of the performance profile (tuple/key counts over time) of the analysis query.

The evolutionary process explores different anonymization pipelines to find a set of non-dominated solutions representing the best possible compromises between these objectives. The anonymization query can be composed of operators like `filter`, `map_noise`, `map_duplicate`, and `map_aggregate`.

## Project Structure
Here is a high-level overview of the project's structure:

*   **/src/main/java/**: Contains all the Java source code, including the problem definition, metrics, query logic, and grammar generators.

*   **/src/main/resources/**: Contains all resource files
    *  `datasets/`: Includes the datasets used for the experiments.
    *  `grammars/`: Includes pre-generated grammar (.bnf) files for the available datasets.

*   **experiment.txt**: The main configuration file for a 3-objective experiment.

*   **experiment_2objResPrivacy.txt**: A configuration file for a simplified 2-objective experiment: privacy and results similarity.

*   **experiment_2objPerfPrivacy.txt**: A configuration file for a simplified 2-objective experiment: privacy and performance similarity.

## How to Run an Experiment

This project is designed to be configured and run entirely from an experiment file (`.txt`) using the JGEA experimenter.

### 1. Datasets

The project includes two main sets of datasets located in `src/main/resources/datasets/`:

*   `airQuality_parallel.csv`, `airQuality_withSensorID.csv`
*   `geolife_5mins.csv`, `geolife_60mins.csv`

**Using a Custom Dataset**

You can use your own dataset by placing it inside the directory `src/main/resources/datasets/` and following these conventions:

1. **Format:** The file must be a standard CSV with a **comma (,)** as the delimiter and a **period (.)** as the decimal separator. Missing values must be explicitly represented as `NaN`.

2. **Timestamp Column:** A column named exactly `timestamp` must be present. Its values must be Unix timestamps in **milliseconds**.

3. **Data Sorting:** The dataset should be sorted by the timestamp column in non-decreasing (ascending) order.

4. **Key Column:** If your dataset has a column that identifies parallel streams (e.g., a user ID or sensor ID), you have to specify its name in the experiment file. This column will be used as the partitioning key and will be excluded from streaming operators

**Analysis Queries**

The evaluation of both Results Similarity and Performance Similarity is based on the execution of a fixed query (Q) over the original and modified data streams.

For the datasets provided with this project, two queries are already implemented:

- `/src/main/java/jgea/query/MainQueryAirQuality.java` for the **AirQuality** datasets
- `/src/main/java/jgea/query/MainQueryGeoLife.java` for the **GeoLife** datasets

The appropriate query is automatically selected inside the problem definition classes based on the dataset path.

When using a **custom dataset**, a corresponding analysis query must be implemented by the user and integrated into the problem definition classes (`StreamAnonymizationProblem.java`, `StreamAnonymizationProblem_2ObjectivesRes.java` and `StreamAnonymizationProblem_2ObjectivesPerf.java`).

This custom query should follow the same structure as the provided examples (`MainQueryAirQuality.java` and `MainQueryGeoLife.java`) 
and must define the streaming operators relevant to the analysis, specify the temporal resolution for the performance metric, and collect performance statistics using key and tuple recorders.

**Results Similarity Configuration**

The Results Similarity metric is implemented in the configurable class `F1Score.java`.
When instantiating the problem definition classes (e.g., `StreamAnonymizationProblem.java`), the constructor of F1Score requires:

* A list of numeric attributes to be compared.
* A relative tolerance threshold expressed as a percentage.

For example for the dataset AirQuality:

```
new F1Score(0.15, List.of("CO(GT)", "NO2(GT)"));
```

### 2. Generate the Grammar

Before running an experiment with your own dataset, you must generate the grammar file that defines the search space. Run the `main` method of the grammar generator classes found in `src/main/java/jgea/grammar/`. You need to configure
`csvPath` (the path to the dataset), `keyColumn` (the name of the partitioning key column), `grammarPath` (the path where the generated .bnf file will be saved).
Multiple grammar generator classes are provided, each enabling a different subset of operators in the search space (e.g., filter-only, filter + map, and filter + map + aggregate). Users should run the generator that matches the intended experimental configuration.

**Provided Grammars**

Pre-generated grammars are available:

* For **AirQuality** in `src/main/resources/grammars/airQuality/`:

        airQuality_generated-grammar-aggregate.bnf

        airQuality_generated-grammar-filters.bnf

        airQuality_generated-grammar-map.bnf

* For **Geolife** in `src/main/resources/grammars/geolife/`:

        geolife_generated-grammar-aggregate.bnf

        geolife_generated-grammar-filters.bnf

        geolife_generated-grammar-map.bnf

### 3. Configure the Experiment File

The experiment file (e.g., experiment.txt) allows to configure the entire experiment.

The file `experiment.txt` is provided to run the problem with three objectives. The file `experiment_2objResPrivacy.txt` is used for the two-objective configuration considering privacy and result similarity, while `experiment_2objPerfPrivacy.txt` is used for the two-objective configuration considering privacy and performance similarity.

In these files the `representation` block must point to the correct **grammar file** and the `problem` block must point to the correct **input file** and **grammar file** and must select the **privacy metric** to use and the **keyColumn**.

**Example 1: 3-Objective Experiment `experiment.txt`**
```
...
      representation = ea.r.cfgTree(grammar = ea.grammar.fromFile(
        path = "src/main/resources/grammars/airQuality/airQuality_generated-grammar-aggregate.bnf"));
...
    problem = anonym.problem.anonymizationProblem(
      inputCsvPath = "datasets/airQuality_parallel.csv";
      grammarPath = "src/main/resources/grammars/airQuality/airQuality_generated-grammar-aggregate.bnf";
      privacyMetric = K_ANONYMITY_CARDINALITY_MAX;
      keyColumn = "SensorID"
    )
```

For **privacy evaluation**, the following metrics are available and can be selected in the experiment configuration `.txt` file as follows:
* `K_ANONYMITY_CARDINALITY`: An advanced metric that combines k-anonymity with a cardinality penalty.
* `K_ANONYMITY_CARDINALITY_MAX`: The k_anonymity metric with a cardinality penalty, the privacy score is based on the maximum standard deviation found across all tuples.
* `K_ANONYMITY_CARDINALITY_Q99`: The k_anonymity metric with a cardinality penalty, the privacy score is based on the 99th percentile of the standard deviation.

### 4. Build the Project

Before building this project, it is required to locally build a customized version of the **Liebre** stream processing framework.

#### Clone and build Liebre

Clone the Liebre repository directly on the `rich_agg` branch:

```
git clone -b rich_agg https://github.com/vincenzo-gulisano/Liebre.git
cd Liebre
```

Build Liebre locally and install it in your local Maven repository:

```
mvn clean install
```

#### Build this project

Compile this project from its root directory:

```
mvn clean install
```

**Java version**:
**Java 21** is required to build and run the project due to compatibility constraints of the JGEA framework, which requires JDK 21.

### 5. Run the Experiment

Execute the JAR from the project’s root directory, pointing to your experiment file:

```
java -jar target/Shield-1.0-SNAPSHOT-jar-with-dependencies.jar -v -nt 10 -f experiment.txt
```
##### Key Command-Line Arguments:

* `-f experiment.txt` — (Required) Specifies the path to the experiment definition file.

* `-nt 10` — (Concurrency) Sets the number of threads for parallel fitness evaluations (e.g., 10).

* `-v` — (Verbose) Enables detailed output and progress information in the console.