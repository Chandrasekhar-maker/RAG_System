import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart';

void main() => runApp(const RagApp());

class RagApp extends StatelessWidget {
  const RagApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
    debugShowCheckedModeBanner: false,
    title: 'Document RAG Assistant',
    theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
    home: const RagHomePage(),
  );
}

class ChatHistoryEntry {
  const ChatHistoryEntry({
    required this.id,
    required this.question,
    required this.answer,
    required this.createdAt,
  });

  final int id;
  final String question;
  final String answer;
  final DateTime createdAt;

  factory ChatHistoryEntry.fromJson(Map<String, dynamic> json) =>
      ChatHistoryEntry(
        id: json['id'] as int,
        question: json['question'] as String,
        answer: json['answer'] as String,
        createdAt: DateTime.parse(json['createdAt'] as String).toLocal(),
      );
}

class RagHomePage extends StatefulWidget {
  const RagHomePage({super.key});

  @override
  State<RagHomePage> createState() => _RagHomePageState();
}

class _RagHomePageState extends State<RagHomePage> {
  static const String baseUrl = 'http://localhost:8080';

  final TextEditingController questionController = TextEditingController();
  PlatformFile? selectedFile;
  List<ChatHistoryEntry> history = [];
  ChatHistoryEntry? selectedHistory;
  bool isUploading = false;
  bool isAsking = false;
  bool isLoadingHistory = true;
  String uploadMessage = '';
  String historyError = '';
  String answer = '';

  @override
  void initState() {
    super.initState();
    loadHistory();
  }

  Future<void> loadHistory() async {
    setState(() {
      isLoadingHistory = true;
      historyError = '';
    });
    try {
      final response = await http.get(Uri.parse('$baseUrl/history'));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception('Backend returned ${response.statusCode}');
      }
      final items = (jsonDecode(response.body) as List<dynamic>)
          .map(
            (item) => ChatHistoryEntry.fromJson(item as Map<String, dynamic>),
          )
          .toList();
      if (!mounted) return;
      setState(() => history = items);
    } catch (e) {
      if (!mounted) return;
      setState(() => historyError = 'Could not load saved history: $e');
    } finally {
      if (mounted) setState(() => isLoadingHistory = false);
    }
  }

  void startNewChat() {
    setState(() {
      selectedHistory = null;
      answer = '';
      uploadMessage = '';
      questionController.clear();
    });
  }

  void openHistory(ChatHistoryEntry entry) {
    setState(() {
      selectedHistory = entry;
      questionController.text = entry.question;
      answer = entry.answer;
    });
    Navigator.of(context).maybePop();
  }

  Future<void> pickPdf() async {
    try {
      final file = await FilePicker.pickFile(
        type: FileType.custom,
        allowedExtensions: ['pdf'],
      );
      if (file == null || !mounted) return;
      setState(() {
        selectedFile = file;
        uploadMessage = '';
      });
    } catch (e) {
      if (mounted) setState(() => uploadMessage = 'File selection failed: $e');
    }
  }

  Future<void> uploadPdf() async {
    final file = selectedFile;
    if (file == null) {
      setState(() => uploadMessage = 'Please select a PDF first.');
      return;
    }
    setState(() {
      isUploading = true;
      uploadMessage = '';
    });
    try {
      final request = http.MultipartRequest(
        'POST',
        Uri.parse('$baseUrl/upload'),
      );
      request.files.add(
        http.MultipartFile.fromBytes(
          'file',
          await file.readAsBytes(),
          filename: file.name,
          contentType: MediaType('application', 'pdf'),
        ),
      );
      final streamed = await request.send();
      final response = await http.Response.fromStream(streamed);
      if (!mounted) return;
      setState(
        () => uploadMessage =
            response.statusCode >= 200 && response.statusCode < 300
            ? response.body
            : 'Upload failed (${response.statusCode}): ${response.body}',
      );
    } catch (e) {
      if (mounted) setState(() => uploadMessage = 'Upload failed: $e');
    } finally {
      if (mounted) setState(() => isUploading = false);
    }
  }

  Future<void> askQuestion() async {
    final question = questionController.text.trim();
    if (question.isEmpty) {
      setState(() => answer = 'Please enter a question.');
      return;
    }
    setState(() {
      isAsking = true;
      selectedHistory = null;
      answer = '';
    });
    try {
      final uri = Uri.parse('$baseUrl/rag')
          .replace(queryParameters: {'question': question});
      final response = await http.get(uri);
      if (!mounted) return;
      setState(
        () => answer = response.statusCode >= 200 && response.statusCode < 300
            ? response.body
            : 'Request failed (${response.statusCode}): ${response.body}',
      );
      if (response.statusCode >= 200 && response.statusCode < 300) {
        await loadHistory();
        if (history.isNotEmpty && mounted) {
          setState(() => selectedHistory = history.first);
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() => answer = 'Unable to contact the backend. Details: $e');
      }
    } finally {
      if (mounted) setState(() => isAsking = false);
    }
  }

  @override
  void dispose() {
    questionController.dispose();
    super.dispose();
  }

  Widget buildHistorySidebar() => Material(
    color: Theme.of(context).colorScheme.surfaceContainerLow,
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 20, 16, 12),
          child: FilledButton.icon(
            onPressed: startNewChat,
            icon: const Icon(Icons.add),
            label: const Text('New question'),
          ),
        ),
        const Padding(
          padding: EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Text('History', style: TextStyle(fontWeight: FontWeight.bold)),
        ),
        if (isLoadingHistory) const LinearProgressIndicator(),
        if (historyError.isNotEmpty)
          Padding(
            padding: const EdgeInsets.all(16),
            child: Text(
              historyError,
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          ),
        Expanded(
          child: history.isEmpty && !isLoadingHistory
              ? const Center(child: Text('No questions yet'))
              : ListView.builder(
                  itemCount: history.length,
                  itemBuilder: (context, index) {
                    final item = history[index];
                    return ListTile(
                      selected: selectedHistory?.id == item.id,
                      leading: const Icon(Icons.chat_bubble_outline),
                      title: Text(
                        item.question,
                        maxLines: 2,
                        overflow: TextOverflow.ellipsis,
                      ),
                      subtitle: Text(
                        '${item.createdAt.toLocal()}'.split('.').first,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                      ),
                      onTap: () => openHistory(item),
                    );
                  },
                ),
        ),
      ],
    ),
  );

  Widget buildWorkspace() => SingleChildScrollView(
    padding: const EdgeInsets.all(24),
    child: Center(
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 850),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Ask questions about your documents',
              style: TextStyle(fontSize: 26, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            const Text('Upload a PDF and get answers using your RAG backend.'),
            const SizedBox(height: 24),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '1. Upload your PDF',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 16),
                    OutlinedButton.icon(
                      onPressed: isUploading ? null : pickPdf,
                      icon: const Icon(Icons.attach_file),
                      label: const Text('Choose PDF'),
                    ),
                    if (selectedFile != null)
                      Text('Selected: ${selectedFile!.name}'),
                    const SizedBox(height: 12),
                    FilledButton.icon(
                      onPressed: isUploading ? null : uploadPdf,
                      icon: isUploading
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.cloud_upload),
                      label: Text(isUploading ? 'Uploading...' : 'Upload PDF'),
                    ),
                    if (uploadMessage.isNotEmpty) ...[
                      const SizedBox(height: 16),
                      SelectableText(uploadMessage),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text(
                      '2. Ask your question',
                      style: TextStyle(
                        fontSize: 20,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 16),
                    TextField(
                      controller: questionController,
                      minLines: 1,
                      maxLines: 4,
                      decoration: const InputDecoration(
                        border: OutlineInputBorder(),
                        hintText: 'Enter your question here...',
                      ),
                      onSubmitted: (_) => askQuestion(),
                    ),
                    const SizedBox(height: 16),
                    FilledButton.icon(
                      onPressed: isAsking ? null : askQuestion,
                      icon: isAsking
                          ? const SizedBox(
                              width: 18,
                              height: 18,
                              child: CircularProgressIndicator(strokeWidth: 2),
                            )
                          : const Icon(Icons.send),
                      label: Text(isAsking ? 'Thinking...' : 'Ask Question'),
                    ),
                  ],
                ),
              ),
            ),
            if (answer.isNotEmpty) ...[
              const SizedBox(height: 16),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(24),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        selectedHistory == null ? 'Answer' : 'Previous answer',
                        style: const TextStyle(
                          fontSize: 20,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const Divider(height: 24),
                      SelectableText(
                        answer,
                        style: const TextStyle(fontSize: 16, height: 1.6),
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ],
        ),
      ),
    ),
  );

  @override
  Widget build(BuildContext context) => LayoutBuilder(
    builder: (context, constraints) {
      final wide = constraints.maxWidth >= 900;
      return Scaffold(
        appBar: AppBar(
          title: const Text('Document RAG Assistant'),
          centerTitle: true,
        ),
        drawer: wide
            ? null
            : Drawer(child: SafeArea(child: buildHistorySidebar())),
        body: wide
            ? Row(
                children: [
                  SizedBox(width: 290, child: buildHistorySidebar()),
                  const VerticalDivider(width: 1),
                  Expanded(child: buildWorkspace()),
                ],
              )
            : buildWorkspace(),
      );
    },
  );
}
