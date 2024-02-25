const { GPT } = require('@openai/gpt');

// Instantiate the GPT model
const gpt = new GPT({
  apiKey: 'your_openai_api_key', // replace with your actual OpenAI API key
  model: 'text-davinci-003', // specify the model you want to use
  temperature: 0.7,
  maxTokens: 150
});

// Example prompt
const prompt = "Translate the following English text to French: 'Hello, how are you?'";

// Generate response
async function generateResponse(prompt) {
  try {
    // Get completion
    const completion = await gpt.complete(prompt);

    // Print the response
    console.log(completion.choices[0].text);
  } catch (error) {
    console.error('Error:', error);
  }
}

// Call the function to generate response
generateResponse(prompt);
