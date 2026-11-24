package app.genesis.mobile

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

object NativeWorkloadEngine {
    private fun checkStop(shouldStop: () -> Boolean) {
        if (shouldStop()) throw IllegalStateException("WORKER_STOP_REQUESTED")
    }
    private fun number(p: JSONObject, key: String, default: Double? = null): Double {
        if (!p.has(key) && default != null) return default
        val value = p.optDouble(key, Double.NaN)
        require(value.isFinite()) { "$key must be finite" }
        return value
    }
    private fun int(p: JSONObject, key: String, default: Int, min: Int, max: Int): Int {
        val v = if (p.has(key)) p.optInt(key, Int.MIN_VALUE) else default
        require(v in min..max) { "$key must be an integer from $min to $max" }
        return v
    }
    private fun matrix(p: JSONObject, shouldStop: () -> Boolean): JSONObject {
        val a = p.getJSONArray("a"); val b = p.getJSONArray("b")
        require(a.length() in 1..40 && b.length() in 1..40) { "Matrix dimensions exceed 40" }
        val rowsA = Array(a.length()) { i -> a.getJSONArray(i) }
        val rowsB = Array(b.length()) { i -> b.getJSONArray(i) }
        val cols = rowsA[0].length(); val bCols = rowsB[0].length()
        require(cols in 1..40 && bCols in 1..40 && rowsB.all { it.length() == bCols } && rowsA.all { it.length() == cols } && cols == rowsB.size) { "Incompatible or non-rectangular matrices" }
        val out = JSONArray()
        for (i in rowsA.indices) {
            checkStop(shouldStop)
            val row = JSONArray()
            for (j in 0 until bCols) {
                var sum = 0.0
                for (k in 0 until cols) {
                    if (k % 16 == 0) checkStop(shouldStop)
                    sum += rowsA[i].getDouble(k) * rowsB[k].getDouble(j)
                }
                row.put(sum)
            }
            out.put(row)
        }
        return JSONObject().put("rows", rowsA.size).put("columns", bCols).put("result", out)
    }
    private fun stats(p: JSONObject, shouldStop: () -> Boolean): JSONObject {
        val values = p.getJSONArray("values")
        require(values.length() in 1..100000) { "values must contain 1 to 100000 numbers" }
        val xs = DoubleArray(values.length())
        var sum = 0.0
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (i in 0 until values.length()) {
            if (i % 1024 == 0) checkStop(shouldStop)
            val x = values.getDouble(i)
            require(x.isFinite())
            xs[i] = x
            sum += x
            min = minOf(min, x)
            max = maxOf(max, x)
        }
        val mean = sum / xs.size
        var varianceSum = 0.0
        for (i in xs.indices) {
            if (i % 1024 == 0) checkStop(shouldStop)
            varianceSum += (xs[i] - mean) * (xs[i] - mean)
        }
        val variance = varianceSum / xs.size
        return JSONObject().put("count", xs.size).put("mean", mean).put("variance", variance)
            .put("standardDeviation", sqrt(variance)).put("min", min).put("max", max)
    }
    private fun probability(p: JSONObject, shouldStop: () -> Boolean): JSONObject {
        val trials = int(p, "trials", 100000, 1, 1000000)
        val probability = number(p, "probability")
        require(probability in 0.0..1.0) { "probability must be between 0 and 1" }
        var state = int(p, "seed", 1, 0, Int.MAX_VALUE)
        var successes = 0
        for (i in 0 until trials) {
            if (i % 1024 == 0) checkStop(shouldStop)
            state += 0x6D2B79F5
            var t = state
            t = (t xor (t ushr 15)) * (t or 1)
            t = t xor (t + ((t xor (t ushr 7)) * (t or 61)))
            val random = (t xor (t ushr 14)).toUInt().toDouble() / 4294967296.0
            if (random < probability) successes++
        }
        return JSONObject().put("trials", trials).put("successes", successes)
            .put("probabilityEstimate", successes.toDouble() / trials).put("expectedProbability", probability).put("seed", p.optInt("seed", 1))
    }
    private fun optimize(p: JSONObject, shouldStop: () -> Boolean): JSONObject {
        val a=number(p,"a",1.0); val b=number(p,"b",0.0); val c=number(p,"c",0.0)
        val min=number(p,"min",-100.0); val max=number(p,"max",100.0)
        val steps=int(p,"steps",10000,2,100000)
        require(min < max) { "min must be less than max" }
        var bestX=min; var bestY=a*min*min+b*min+c
        for (i in 1..steps) {
            if (i % 256 == 0) checkStop(shouldStop)
            val x=min+(max-min)*i/steps; val y=a*x*x+b*x+c
            if (y<bestY) { bestX=x; bestY=y }
        }
        return JSONObject().put("x",bestX).put("value",bestY).put("evaluations",steps+1).put("objective","a*x^2 + b*x + c")
    }
    private fun arithmeticWorkload(p: JSONObject, progress: (Int, Int, String) -> Unit, shouldStop: () -> Boolean): JSONObject {
        val equations = int(p, "equations", 4000, 100, 10000)
        val iterations = int(p, "iterationsPerEquation", 10000, 100, 20000)
        val started = System.nanoTime()
        var checksum = 0.0
        var lastValue = 0.0
        for (equation in 1..equations) {
            checkStop(shouldStop)
            val x = equation.toDouble() / equations
            var sum = 0.0
            for (step in 1..iterations) {
                if (step % 256 == 0) checkStop(shouldStop)
                val angle = x + step * 0.000001
                sum += kotlin.math.sin(angle) * kotlin.math.cos(angle * 0.5) + sqrt(kotlin.math.abs(x * angle) + 1.0)
            }
            lastValue = sum
            checksum += sum
            progress(equation, equations, "Equation $equation / $equations")
        }
        val elapsedMs = (System.nanoTime() - started) / 1e6
        return JSONObject().put("equationsCompleted", equations).put("iterationsPerEquation", iterations)
            .put("totalEvaluations", equations.toLong() * iterations.toLong()).put("checksum", checksum)
            .put("lastEquationValue", lastValue).put("elapsedMs", elapsedMs)
            .put("note", "Elapsed time depends on device load and thermal state; no artificial delay is added.")
    }

    fun execute(
        workload: String,
        p: JSONObject,
        progress: (Int, Int, String) -> Unit = { _, _, _ -> },
        shouldStop: () -> Boolean = { false },
        appFilesDir: File? = null
    ): JSONObject {
        checkStop(shouldStop)
        val output = when(workload) {
            "arithmetic-workload" -> arithmeticWorkload(p, progress, shouldStop)
            "zip-archive" -> ZipArchiveWorkload.execute(p, shouldStop)
            "zip-archive-stream" -> ZipArchiveStreamWorkload.execute(p, shouldStop)
            "android-build-source-validate" -> AndroidBuildSourceValidationWorkload.execute(p, shouldStop)
            "android-build-toolchain" -> AndroidBuildToolchainWorkload.execute(p, appFilesDir, shouldStop)
            "android-build-compile-dex" -> AndroidBuildCompileDexWorkload.execute(p, appFilesDir, shouldStop)
            "android-build-compile-package" -> AndroidBuildCompilePackageWorkload.execute(p, appFilesDir, shouldStop)
            "android-build-package" -> AndroidBuildPackageWorkload.execute(p, shouldStop)
            "matrix-computation" -> matrix(p, shouldStop)
            "statistical-analysis" -> stats(p, shouldStop)
            "probability-simulation" -> probability(p, shouldStop)
            "math-optimization", "parameter-optimization" -> optimize(p, shouldStop)
            "small-simulation" -> {
                val steps=int(p,"steps",1000,1,100000); var value=number(p,"initial",1.0)
                val multiplier=number(p,"multiplier",1.0); val additive=number(p,"additive",0.0)
                var min=value; var max=value
                for (i in 0 until steps) {
                    if (i % 256 == 0) checkStop(shouldStop)
                    value=value*multiplier+additive
                    require(value.isFinite()) { "Simulation diverged" }
                    min=kotlin.math.min(min,value); max=kotlin.math.max(max,value)
                }
                JSONObject().put("steps",steps).put("finalValue",value).put("min",min).put("max",max)
            }
            "algorithm-comparison" -> {
                val values=p.getJSONArray("values"); require(values.length() in 1..10000)
                val xs=DoubleArray(values.length()) { values.getDouble(it) }
                checkStop(shouldStop)
                val t0=System.nanoTime(); val native=xs.sortedArray(); val t1=System.nanoTime()
                fun mergeSort(input: DoubleArray): DoubleArray {
                    checkStop(shouldStop)
                    if (input.size < 2) return input.copyOf()
                    val mid=input.size/2
                    val left=mergeSort(input.copyOfRange(0,mid)); val right=mergeSort(input.copyOfRange(mid,input.size))
                    val out=DoubleArray(input.size); var i=0; var j=0; var k=0
                    while(i<left.size && j<right.size) {
                        if (k % 256 == 0) checkStop(shouldStop)
                        out[k++]=if(left[i]<=right[j]) left[i++] else right[j++]
                    }
                    while(i<left.size) out[k++]=left[i++]
                    while(j<right.size) out[k++]=right[j++]
                    return out
                }
                val merge=mergeSort(xs); val t2=System.nanoTime()
                JSONObject().put("itemCount",xs.size).put("outputsMatch",native.contentEquals(merge))
                    .put("nativeSortMs",(t1-t0)/1e6).put("mergeSortMs",(t2-t1)/1e6)
            }
            "benchmark" -> {
                val size=int(p,"size",18,2,40); val iterations=int(p,"iterations",5,1,100)
                val a=Array(size){i->DoubleArray(size){j->(i+j+1).toDouble()/size}}
                val b=Array(size){i->DoubleArray(size){j->if(i==j) 1.0 else 0.01*(i+j)}}
                val start=System.nanoTime(); var checksum=0.0
                repeat(iterations){ n->
                    checkStop(shouldStop)
                    val out=Array(size){DoubleArray(size)}
                    for(i in 0 until size) {
                        checkStop(shouldStop)
                        for(k in 0 until size) for(j in 0 until size) out[i][j]+=a[i][k]*b[k][j]
                    }
                    checksum+=out[n%size][(n*7)%size]
                }
                JSONObject().put("size",size).put("iterations",iterations).put("elapsedMs",(System.nanoTime()-start)/1e6).put("checksum",checksum)
                    .put("note","Timing is device dependent; compare under similar conditions.")
            }
            else -> throw IllegalArgumentException("Unsupported workload: $workload")
        }
        checkStop(shouldStop)
        return output
    }
}
