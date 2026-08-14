import os
import google.generativeai as genai

api_key = os.environ.get("GEMINI_API_KEY")
if not api_key:
    raise ValueError("GEMINI_API_KEY is not set!")

genai.configure(api_key=api_key)
model = genai.GenerativeModel('gemini-3.7-flash')

def ask_gemini(prompt_text):
    response = model.generate_content(prompt_text)
    print("Gemini Response:\n", response.text)

if __name__ == "__main__":
    ask_gemini("Review the Castivio TV project structure and give optimization tips for Android TV.")
