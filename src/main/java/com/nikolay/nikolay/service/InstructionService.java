package com.nikolay.nikolay.service;

import com.nikolay.nikolay.model.Instruction;
import com.nikolay.nikolay.repository.InstructionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class InstructionService {
    private final InstructionRepository instructionRepository;
    private final FileStorageService fileStorageService;

    public InstructionService(InstructionRepository instructionRepository, FileStorageService fileStorageService) {
        this.instructionRepository = instructionRepository;
        this.fileStorageService = fileStorageService;
    }

    public List<Instruction> getAllInstructions() {
        return instructionRepository.findAll();
    }

    public Optional<Instruction> getInstructionById(Long id) {
        return instructionRepository.findById(id);
    }

    public void saveInstruction(Instruction instruction) {
        System.out.println("Сохранение инструкции: " + instruction.getId() + ", контент: " + instruction.getContent());
        instructionRepository.save(instruction);
    }

    public void deleteInstruction(Long id) {
        instructionRepository.deleteById(id);
    }

}
