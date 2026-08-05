package org.offlinemesh.app.sim

import kotlin.random.Random

/** The two static endpoints and the transition time [SimNetwork.degreeRamp] interpolates between —
 *  bundled so that function's parameter list stays short. */
data class DegreeRampSpec(val fromDegree: Int, val toDegree: Int, val transitionMs: Long)

/**
 * Neighbour adjacency for a scenario, as a function of simulated time — a static map for S1/S7,
 * a time-varying one for the mobility scenarios (S3/S4). Kept as a single function type rather
 * than a class hierarchy so scenario code stays a plain data builder.
 */
class SimNetwork(
    val nodes: List<SimNode>,
    val random: Random = Random(SEED),
    private val adjacencyAt: (nowMs: Long) -> Map<String, List<SimNode>>,
) {
    fun neighborsOf(node: SimNode, nowMs: Long): List<SimNode> = adjacencyAt(nowMs)[node.id].orEmpty()

    companion object {
        const val SEED = 20072026L

        /** Every node hears every other, always — the D = N-1 topology: S1 "three in a room"
         *  at N=3, S7 "Kettle" at N=400. */
        fun fullMesh(nodes: List<SimNode>, random: Random = Random(SEED)): SimNetwork {
            val map = nodes.associate { node -> node.id to nodes.filter { it.id != node.id } }
            return SimNetwork(nodes, random) { map }
        }

        /** A fixed random [degree]-regular-ish graph — degree as an independent variable from
         *  node count, e.g. S2's "5-person group findable among 400 strangers" (each node hears a
         *  realistic handful of neighbours, not literally all 399 others). */
        fun randomRegular(nodes: List<SimNode>, degree: Int, random: Random = Random(SEED)): SimNetwork {
            val map = nodes.associate { node ->
                node.id to nodes.filter { it.id != node.id }.shuffled(random).take(degree)
            }
            return SimNetwork(nodes, random) { map }
        }

        /** Linear ramp of [subject]'s degree between two static regimes over [DegreeRampSpec] —
         *  S4 "walking in" (low -> high) and S3 "walking out" (high -> low) are the same shape
         *  with the endpoints swapped. The rest of [nodes] form a static crowd [subject] moves
         *  through, hearing each other fully throughout (they are not the one moving). */
        fun degreeRamp(
            nodes: List<SimNode>,
            subject: SimNode,
            spec: DegreeRampSpec,
            random: Random = Random(SEED),
        ): SimNetwork {
            val crowd = nodes.filter { it.id != subject.id }
            val shuffledCrowd = crowd.shuffled(random)
            val staticCrowdAdjacency = crowd.associate { n -> n.id to crowd.filter { it.id != n.id } }
            return SimNetwork(nodes, random) { nowMs ->
                val t = (nowMs.toDouble() / spec.transitionMs).coerceIn(0.0, 1.0)
                val range = spec.toDegree - spec.fromDegree
                val degreeNow = (spec.fromDegree + range * t).toInt().coerceIn(0, crowd.size)
                val subjectNeighbors = shuffledCrowd.take(degreeNow)
                val withSubject = staticCrowdAdjacency.mapValues { (nodeId, existing) ->
                    if (nodeId in subjectNeighbors.map { it.id }) existing + subject else existing
                }
                withSubject + (subject.id to subjectNeighbors)
            }
        }
    }
}
