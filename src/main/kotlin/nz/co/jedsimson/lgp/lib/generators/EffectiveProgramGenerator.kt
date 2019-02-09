package nz.co.jedsimson.lgp.lib.generators

import nz.co.jedsimson.lgp.core.environment.Environment
import nz.co.jedsimson.lgp.core.program.Program
import nz.co.jedsimson.lgp.core.program.ProgramGenerator
import nz.co.jedsimson.lgp.core.evolution.operators.choice
import nz.co.jedsimson.lgp.core.evolution.operators.randInt
import nz.co.jedsimson.lgp.core.program.registers.RegisterType
import nz.co.jedsimson.lgp.core.modules.ModuleInformation
import nz.co.jedsimson.lgp.core.program.Output
import nz.co.jedsimson.lgp.core.program.instructions.*
import nz.co.jedsimson.lgp.core.program.registers.RandomRegisterGenerator
import nz.co.jedsimson.lgp.core.program.registers.RegisterSet
import nz.co.jedsimson.lgp.core.program.registers.getRandomInputAndCalculationRegisters
import nz.co.jedsimson.lgp.lib.base.BaseInstruction
import nz.co.jedsimson.lgp.lib.base.BaseProgram

internal class EffectiveProgramInstructionGenerator<TProgram, TOutput : Output<TProgram>> :
    InstructionGenerator<TProgram, TOutput> {

    private val random = this.environment.randomState
    private val operationPool: List<Operation<TProgram>>
    private val registers: RegisterSet<TProgram>
    private val registerGenerator: RandomRegisterGenerator<TProgram>

    constructor(environment: Environment<TProgram, TOutput>) : super(environment) {
        this.operationPool = environment.operations
        this.registers = this.environment.registerSet.copy()
        this.registerGenerator = RandomRegisterGenerator(this.environment.randomState, this.registers)
    }

    /**
     * Not implemented -- this implementation should only be used internally by [EffectiveProgramGenerator].
     */
    override fun generateInstruction(): Instruction<TProgram> {
        throw NotImplementedError()
    }

    /**
     * Generates an effective instruction.
     *
     * @param effectiveRegisters The current set of effective registers.
     * @param branch Determines whether the generated instruction should be a branch instruction.
     */
    fun generateInstruction(effectiveRegisters: List<RegisterIndex>, branch: Boolean = false): Instruction<TProgram> {
        // If there are no effective registers, then default to the first calculation register.
        val outputs = when {
            effectiveRegisters.isEmpty() -> listOf(this.registers.calculationRegisters.first)
            else -> effectiveRegisters
        }

        // Pick a random operation for this instruction.
        val operation = when {
            branch -> {
                this.random.choice(this.operationPool.filter { operation ->
                    operation is BranchOperation<TProgram>
                })
            }
            else -> {
                this.random.choice(this.operationPool)
            }
        }

        val shouldUseConstant = random.nextFloat().toDouble() < this.environment.configuration.constantsRate
        // Pick a random (but effective) register.
        val output = this.random.choice(outputs)

        if (shouldUseConstant) {
            // This instruction should use a constant register, first get the constant register
            val constRegister = this.registerGenerator.next(RegisterType.Constant).first().index

            // Then choose either a calculation or input register. We use arity - 1 because whatever the arity of
            // the operation, we've already chosen delegated one of the arguments registers as a constant register.
            val nonConstRegisters = this.registerGenerator.getRandomInputAndCalculationRegisters(
                operation.arity.number - 1
            )

            // We don't always want the constant register to be in the same position in an instruction.
            val inputs = when {
                this.random.nextFloat() < 0.5 -> {
                    listOf(constRegister) + nonConstRegisters
                }
                else -> {
                    nonConstRegisters + listOf(constRegister)
                }
            }

            return BaseInstruction(operation, output, inputs.toMutableList())
        } else {
            // Choose some random input registers depending on the arity of the operation
            val inputs = this.registerGenerator.getRandomInputAndCalculationRegisters(operation.arity.number)

            return BaseInstruction(operation, output, inputs)
        }
    }

    override val information = ModuleInformation (
        description = "A simple instruction generator."
    )
}


/**
 * A ``ProgramGenerator`` implementation that provides effective ``BaseProgram`` instances.
 *
 * All programs generated will have an entirely effective set of instructions.
 *
 * @property sentinelTrueValue A value that should be considered as boolean "true".
 * @property outputRegisterIndices A collection of indices that should be considered as the program output registers.
 * @property outputResolver A function that can be used to resolve the programs register contents to an [Output].
 */
class EffectiveProgramGenerator<TProgram, TOutput : Output<TProgram>>(
        environment: Environment<TProgram, TOutput>,
        val sentinelTrueValue: TProgram,
        val outputRegisterIndices: List<RegisterIndex>,
        val outputResolver: (BaseProgram<TProgram, TOutput>) -> TOutput
) : ProgramGenerator<TProgram, TOutput>(
    environment,
    instructionGenerator = EffectiveProgramInstructionGenerator(environment)
) {

    private val random = this.environment.randomState

    override fun generateProgram(): Program<TProgram, TOutput> {
        val length = this.random.randInt(
            this.environment.configuration.initialMinimumProgramLength,
            this.environment.configuration.initialMaximumProgramLength
        )

        val branchesUsed = this.environment.operations.any { op -> op is BranchOperation<TProgram> }
        val branchInitialisationRate = this.environment.configuration.branchInitialisationRate
        val output = this.outputRegisterIndices.first()

        val instructions = mutableListOf<Instruction<TProgram>>()
        val effectiveRegisters = mutableListOf(output)

        instructions.add(
            (this.instructionGenerator as EffectiveProgramInstructionGenerator<TProgram, TOutput>).generateInstruction(
                effectiveRegisters
            )
        )

        // Construct effective instructions
        for (i in 2..length) {
            // If the previously added instruction was a branch, then it's output register will
            // not be in the effective register set, so we don't attempt to delete it.
            if (instructions.first().operation !is BranchOperation<TProgram>) {
                effectiveRegisters.remove(instructions.first().destination)
            }

            // Add any operand registers that are not already known to be
            // effective and are calculation registers.
            instructions.first().operands.filter { operand ->
                operand !in effectiveRegisters &&
                (this.environment.registerSet.registerType(operand) == RegisterType.Calculation)
            }.forEach { operand ->
                effectiveRegisters.add(operand)
            }

            if (effectiveRegisters.isEmpty()) {
                effectiveRegisters.add(output)
            }

            val instruction = when {
                branchesUsed && random.nextDouble() < branchInitialisationRate -> {
                    this.instructionGenerator.next().first { instruction ->
                        instruction.operation is BranchOperation<TProgram>
                    }
                }
                else -> {
                    // Get a random instruction and make it effective by using one of the registers marked as effective.
                    val instr = this.instructionGenerator.generateInstruction(effectiveRegisters)

                    instr.destination = random.choice(effectiveRegisters)

                    instr
                }
            }

            // We always push the instruction to the front as we are somewhat working backwards.
            instructions.add(0, instruction)
        }

        // Each program gets its own copy of the register set
        val program = BaseProgram(
            instructions = instructions.toList(),
            registerSet = this.environment.registerSet.copy(),
            outputRegisterIndices = this.outputRegisterIndices,
            sentinelTrueValue = this.sentinelTrueValue,
            outputResolver = this.outputResolver
        )

        program.effectiveInstructions = instructions.toMutableList()

        return program
    }

    override val information = ModuleInformation(
            description = "A ProgramGenerator implementation that provides effective BaseProgram instances."
    )
}
