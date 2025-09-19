package com.DeliveryInventoryService.DeliveryInventoryService.Utils;

import java.util.*;

import com.DeliveryInventoryService.DeliveryInventoryService.Model.Order;

public class GeneticAlgorithmSolver {
    private double[][] distanceMatrix;
    private List<Order> orders;
    private int populationSize;
    private int generations;
    private double mutationRate;
    private Random random = new Random();

    public GeneticAlgorithmSolver(double[][] distanceMatrix, List<Order> orders,
            int populationSize, int generations, double mutationRate) {
        this.distanceMatrix = distanceMatrix;
        this.orders = orders;
        this.populationSize = populationSize;
        this.generations = generations;
        this.mutationRate = mutationRate;
    }

    // Each individual is a permutation of order indices
    private List<List<Integer>> initializePopulation() {
        List<List<Integer>> population = new ArrayList<>();
        List<Integer> base = new ArrayList<>();
        for (int i = 0; i < orders.size(); i++)
            base.add(i);

        for (int i = 0; i < populationSize; i++) {
            List<Integer> individual = new ArrayList<>(base);
            Collections.shuffle(individual, random);
            population.add(individual);
        }
        return population;
    }

    private double fitness(List<Integer> route) {
        double total = 0;
        for (int i = 0; i < route.size() - 1; i++) {
            total += distanceMatrix[route.get(i)][route.get(i + 1)];
        }
        return total; // minimize
    }

    private List<Integer> tournamentSelection(List<List<Integer>> population, int k) {
        List<Integer> best = null;
        for (int i = 0; i < k; i++) {
            List<Integer> candidate = population.get(random.nextInt(population.size()));
            if (best == null || fitness(candidate) < fitness(best)) {
                best = candidate;
            }
        }
        return new ArrayList<>(best);
    }

    private List<Integer> crossover(List<Integer> p1, List<Integer> p2) {
        int size = p1.size();
        int start = random.nextInt(size);
        int end = start + random.nextInt(size - start);

        Set<Integer> subset = new HashSet<>(p1.subList(start, end));
        List<Integer> child = new ArrayList<>(p1.subList(start, end));

        for (int i = 0; i < size; i++) {
            int gene = p2.get(i);
            if (!subset.contains(gene)) {
                child.add(gene);
            }
        }
        return child;
    }

    private void mutate(List<Integer> individual) {
        if (random.nextDouble() < mutationRate) {
            int i = random.nextInt(individual.size());
            int j = random.nextInt(individual.size());
            Collections.swap(individual, i, j);
        }
    }

    public List<Integer> solve() {
        List<List<Integer>> population = initializePopulation();
        List<Integer> best = population.get(0);

        for (int gen = 0; gen < generations; gen++) {
            List<List<Integer>> newPopulation = new ArrayList<>();

            while (newPopulation.size() < populationSize) {
                List<Integer> p1 = tournamentSelection(population, 3);
                List<Integer> p2 = tournamentSelection(population, 3);

                List<Integer> child = crossover(p1, p2);
                mutate(child);
                newPopulation.add(child);
            }

            // Replace population
            population = newPopulation;

            // Track best
            for (List<Integer> ind : population) {
                if (fitness(ind) < fitness(best)) {
                    best = ind;
                }
            }
        }
        return best; // return best route
    }
}
